// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/jc2pa/TileSigner.java
package de.christianmahnke.jc2pa;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine;
import de.christianmahnke.iiif.fliiifenleger.wasm.WasmLanePool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Signs IIIF tiles (or any image bytes) with C2PA manifests.
 *
 * <p>WASM instances are neither thread-safe nor allowed to hop host threads,
 * so every lane of the internal pool pairs one interpreter instance with one
 * dedicated worker thread that lazily creates it and never shares it.
 * Separate instances are fully isolated (each owns its linear memory), which
 * is what makes parallel signing sound.  Tasks are routed round-robin across
 * lanes; concurrent callers block for their own result.
 *
 * <p>Parallelism defaults to one lane per available processor capped at 4
 * (opt out with parallelism {@code 1}); a single lane behaves exactly like
 * the previous dedicated signing thread.  Request more or fewer lanes with
 * {@link #TileSigner(String, int)} (or {@code --sink-opt threads=N} on the
 * {@code c2pa} sink); a shared {@link C2paWasm} instance always implies a
 * single lane.
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

    private final WasmLanePool<C2paWasm> lanes;

    private volatile boolean closed = false;

    /**
     * Create a signer that owns its WASM instance(s), using the engine selected
     * by {@code engineSelection} (see {@link WasmEngine#create}).  Uses a
     * single lane; the sinks resolve the core-based default separately.
     *
     * @param engineSelection {@code auto}, {@code chicory}, {@code graalvm},
     *                        or {@code null} for the {@code wasm.engine}
     *                        system property / {@code auto}.
     * @throws IOException if the WASM module cannot be loaded.
     */
    public TileSigner(String engineSelection) throws IOException {
        this(engineSelection, 1);
    }

    /**
     * Create a signer that owns its WASM instance(s), with the given lane
     * parallelism.  {@code 1} selects the classic serial behaviour; larger
     * values sign on that many lanes in parallel.
     *
     * @param engineSelection {@code auto}, {@code chicory}, {@code graalvm},
     *                        or {@code null} for the {@code wasm.engine}
     *                        system property / {@code auto}.
     * @param parallelism     Requested lane count; clamped to the available
     *                        processors (and to 2 for GraalWasm).
     * @throws IOException if the WASM module cannot be loaded.
     * @throws IllegalArgumentException if {@code parallelism < 1}.
     */
    public TileSigner(String engineSelection, int parallelism) throws IOException {
        this(resolveWasmBytes(), engineSelection, parallelism, true);
    }

    /**
     * Create a signer sharing an existing WASM instance.  The caller keeps
     * ownership of the instance.  A shared instance is always serial
     * (parallelism 1).
     *
     * @param wasm Shared {@link C2paWasm} instance.
     */
    public TileSigner(C2paWasm wasm) {
        this(wasm, 1);
    }

    /**
     * Create a signer sharing an existing WASM instance.  The caller keeps
     * ownership of the instance.
     *
     * @param wasm Shared {@link C2paWasm} instance.
     * @param parallelism Must be 1 — a shared instance cannot run on
     *                    parallel lanes.
     * @throws IllegalArgumentException if {@code parallelism != 1}.
     */
    TileSigner(C2paWasm wasm, int parallelism) {
        if (parallelism != 1) {
            throw new IllegalArgumentException(
                "A shared C2paWasm instance supports only parallelism 1, got " + parallelism);
        }
        final C2paWasm shared = wasm;
        this.lanes = new WasmLanePool<>("jc2pa-signer", 1, () -> shared, false);
    }

    private TileSigner(byte[] wasmBytes, String engineSelection, int parallelism, boolean owned)
            throws IOException {
        int lanes = WasmEngine.resolveParallelism(parallelism, engineSelection);
        this.lanes = new WasmLanePool<>("jc2pa-signer", lanes,
            () -> new C2paWasm(wasmBytes, engineSelection), owned);
        // Fail fast on unloadable modules, as the old constructor did.
        this.lanes.initEagerly();
    }

    /** @return The configured lane count (for tests). */
    int laneCount() {
        return lanes.laneCount();
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
        return submit(wasm -> {
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
        return submit(wasm -> {
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
        return submitRead(wasm -> {
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
        return submitRead(wasm -> {
            try (C2paReader reader = C2paReader.fromBytes(wasm, format, assetBytes)) {
                return reader.json();
            }
        });
    }

    /**
     * Read the validation results of an asset's manifest store as JSON.
     * Runs on the dedicated signer thread (all WASM access must happen
     * there — see the class documentation).
     *
     * @param assetBytes Raw asset bytes.
     * @param format     MIME type or file extension, e.g. {@code "image/jpeg"}.
     * @return The validation results JSON string, or {@code null} when the
     *         store has no validation results.
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this signer has been closed.
     */
    public String validationResultsJson(byte[] assetBytes, String format)
            throws C2paException {
        return submitRead(wasm -> {
            try (C2paReader reader = C2paReader.fromBytes(wasm, format, assetBytes)) {
                return reader.validationResultsJson();
            }
        });
    }

    /**
     * Read the validation state of an asset's manifest store:
     * {@code "Valid"}, {@code "Invalid"}, or {@code "Trusted"} (the latter
     * only when the signing chain anchors in trust material installed via
     * {@link #setTrustAnchors}).
     *
     * @param assetBytes Raw asset bytes.
     * @param format     MIME type or file extension, e.g. {@code "image/jpeg"}.
     * @return Validation state string.
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this signer has been closed.
     */
    public String validationState(byte[] assetBytes, String format)
            throws C2paException {
        return submitRead(wasm -> {
            try (C2paReader reader = C2paReader.fromBytes(wasm, format, assetBytes)) {
                return reader.validationState();
            }
        });
    }

    /**
     * Install a PEM trust anchor bundle for subsequently created readers
     * (process-global in the WASM module, like all c2pa-rs settings).
     * Readers then report {@code "Trusted"} instead of merely
     * {@code "Valid"} when the signing chain anchors in the bundle.
     *
     * @param pem PEM bundle (one or more certificates), validated eagerly.
     * @throws C2paException         if the bundle is malformed.
     * @throws IllegalStateException if this signer has been closed.
     */
    public void setTrustAnchors(String pem) throws C2paException {
        submitRead(wasm -> {
            wasm.trustAnchorsSet(pem);
            return null;
        });
    }

    /**
     * Drop previously installed trust anchors; readers go back to
     * unanchored validation.
     *
     * @throws IllegalStateException if this signer has been closed.
     */
    public void clearTrustAnchors() {
        try {
            submitRead(wasm -> {
                wasm.trustAnchorsClear();
                return null;
            });
        } catch (C2paException e) {
            throw new IllegalStateException("Clearing trust anchors failed", e);
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Runs a WASM operation on a pool lane with that lane's instance and
     * blocks for the result.
     *
     * @throws C2paException         when the operation fails inside WASM.
     * @throws IllegalStateException when this signer has been closed.
     */
    private byte[] submit(Function<C2paWasm, byte[]> operation) throws C2paException {
        if (closed) {
            throw new IllegalStateException("TileSigner has been closed");
        }
        Future<byte[]> future = lanes.submit(operation::apply);
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
     * Runs a WASM read operation on a pool lane with that lane's instance
     * and blocks for the result.
     */
    private <T> T submitRead(Function<C2paWasm, T> operation) throws C2paException {
        if (closed) {
            throw new IllegalStateException("TileSigner has been closed");
        }
        Future<T> future = lanes.submit(operation::apply);
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
        lanes.close();
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
