// src/main/java/de/christianmahnke/jc2pa/TileSigner.java
package de.christianmahnke.jc2pa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Signs IIIF tiles (or any image bytes) with C2PA manifests.
 *
 * <p>The service owns a single dedicated signing thread on which ALL WASM
 * access happens.  WASM execution is single-threaded, and the interpreter
 * keeps thread-affine state, so calls from different host threads — even
 * when serialized by a lock — can corrupt the instance.  Routing every
 * operation through one thread avoids this entirely; concurrent callers are
 * queued and their results awaited.
 *
 * <p>Two signing modes exist:
 * <ul>
 *   <li><b>Ephemeral</b> — signs with an ephemeral self-signed certificate
 *       chain.  No key material required; intended for tests and demos.  The
 *       produced manifests will not validate against any trust list.</li>
 *   <li><b>Keys</b> — signs with a PEM certificate chain and PEM private
 *       key supplied per call.  This is the production path.</li>
 * </ul>
 *
 * <p>Each signing operation creates a fresh WASM-side builder, so the
 * service can sign any number of tiles.
 */
public final class TileSigner implements AutoCloseable {

    private final C2paWasm wasm;

    /** Whether this service owns (and must close) the WASM instance. */
    private final boolean ownsWasm;

    /**
     * Dedicated thread for all WASM access — see the class documentation for
     * why calls must not hop between host threads.
     */
    private final ExecutorService wasmExecutor;

    private volatile boolean closed = false;

    /**
     * Create a signer that owns its WASM instance, using the engine selected
     * by {@code engineSelection} (see {@link WasmEngine#create}).
     *
     * @param engineSelection {@code auto}, {@code chicory}, {@code graalvm},
     *                        or {@code null} for the {@code jc2pa.engine}
     *                        system property / {@code auto}.
     * @throws IOException if the WASM module cannot be loaded.
     */
    public TileSigner(String engineSelection) throws IOException {
        this(new C2paWasm(resolveWasmBytes(), engineSelection), true);
    }

    /**
     * Create a signer sharing an existing WASM instance.  The caller keeps
     * ownership of the instance.
     *
     * @param wasm Shared {@link C2paWasm} instance.
     */
    public TileSigner(C2paWasm wasm) {
        this(wasm, false);
    }

    private TileSigner(C2paWasm wasm, boolean ownsWasm) {
        this.wasm     = wasm;
        this.ownsWasm = ownsWasm;
        this.wasmExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jc2pa-signer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Sign a tile with an ephemeral self-signed certificate.
     *
     * @param tileBytes    Raw tile bytes (e.g. JPEG or PNG).
     * @param format       MIME type or file extension, e.g. {@code "image/jpeg"}.
     * @param manifestJson C2PA manifest definition JSON.
     * @param certName     Common name for the ephemeral certificate.
     * @return Signed tile bytes.
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this signer has been closed.
     */
    public byte[] signEphemeral(byte[] tileBytes, String format,
                                String manifestJson, String certName)
            throws C2paException {
        return submit(() -> {
            try (C2paBuilder builder = new C2paBuilder(wasm, manifestJson)) {
                return builder.signEphemeral(format, tileBytes, certName);
            }
        });
    }

    /**
     * Sign a tile using a PEM certificate chain and PEM private key.
     *
     * @param tileBytes    Raw tile bytes (e.g. JPEG or PNG).
     * @param format       MIME type or file extension, e.g. {@code "image/jpeg"}.
     * @param manifestJson C2PA manifest definition JSON.
     * @param certPem      PEM-encoded certificate chain bytes.
     * @param keyPem       PEM-encoded private key bytes.
     * @param alg          Signing algorithm, e.g. {@code "es256"}.
     * @param tsaUrl       Optional timestamp authority URL ({@code null} = none).
     * @return Signed tile bytes.
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this signer has been closed.
     */
    public byte[] sign(byte[] tileBytes, String format,
                       String manifestJson,
                       byte[] certPem, byte[] keyPem,
                       String alg, String tsaUrl)
            throws C2paException {
        return submit(() -> {
            try (C2paBuilder builder = new C2paBuilder(wasm, manifestJson)) {
                return builder.signWithKeys(format, tileBytes, certPem, keyPem,
                                            alg, tsaUrl);
            }
        });
    }

    /**
     * Read the active manifest label of an asset.  Runs on the dedicated
     * signer thread (all WASM access must happen there — see the class
     * documentation).
     *
     * @param assetBytes Raw asset bytes.
     * @param format     MIME type or file extension, e.g. {@code "image/jpeg"}.
     * @return The active manifest label, or {@code null} if the asset has no
     *         manifest.
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this signer has been closed.
     */
    public String activeLabel(byte[] assetBytes, String format)
            throws C2paException {
        return submitRead(() -> {
            try (C2paReader reader = C2paReader.fromBytes(wasm, format, assetBytes)) {
                return reader.activeLabel();
            }
        });
    }

    /**
     * Read the full manifest store JSON of an asset.  Runs on the dedicated
     * signer thread (all WASM access must happen there — see the class
     * documentation).
     *
     * @param assetBytes Raw asset bytes.
     * @param format     MIME type or file extension, e.g. {@code "image/jpeg"}.
     * @return The manifest store JSON string.
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this signer has been closed.
     */
    public String manifestJson(byte[] assetBytes, String format)
            throws C2paException {
        return submitRead(() -> {
            try (C2paReader reader = C2paReader.fromBytes(wasm, format, assetBytes)) {
                return reader.json();
            }
        });
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Submit a WASM operation to the dedicated signer thread and block for
     * the result.
     *
     * @throws C2paException         when the operation fails inside WASM.
     * @throws IllegalStateException when this signer has been closed.
     */
    private byte[] submit(Callable<byte[]> operation) throws C2paException {
        if (closed) {
            throw new IllegalStateException("TileSigner has been closed");
        }
        Future<byte[]> future = wasmExecutor.submit(operation);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new C2paException("Signing was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof C2paException c2paCause) {
                throw c2paCause;
            }
            if (cause instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            throw new C2paException("Signing failed", cause);
        }
    }

    /**
     * Submit a WASM read operation to the dedicated signer thread and block
     * for the result.
     */
    private <T> T submitRead(Callable<T> operation) throws C2paException {
        if (closed) {
            throw new IllegalStateException("TileSigner has been closed");
        }
        Future<T> future = wasmExecutor.submit(operation);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new C2paException("Reading was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof C2paException c2paCause) {
                throw c2paCause;
            }
            if (cause instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            throw new C2paException("Reading failed", cause);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        wasmExecutor.shutdown();
        if (ownsWasm) {
            wasm.close();
        }
    }

    /**
     * Resolve the WASM module bytes the same way {@link C2paWasm#fromClasspath}
     * does, with a file-system fallback for development runs.
     */
    private static byte[] resolveWasmBytes() throws IOException {
        try {
            return C2paWasm.fromClasspathBytes();
        } catch (IOException e) {
            java.nio.file.Path local =
                java.nio.file.Paths.get("src/main/resources/wasm/c2pa_wasm.wasm");
            if (java.nio.file.Files.exists(local)) {
                return java.nio.file.Files.readAllBytes(local);
            }
            throw e;
        }
    }
}
