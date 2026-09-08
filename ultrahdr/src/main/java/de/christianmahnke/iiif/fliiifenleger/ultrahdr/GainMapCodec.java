// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/iiif/fliiifenleger/ultrahdr/GainMapCodec.java
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmMemory;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Splits UltraHDR JPEGs into primary/gain map parts and re-assembles tiles
 * with integrated gain maps.
 *
 * <p>The service owns a single dedicated codec thread on which ALL WASM
 * access happens — the WASM interpreter keeps thread-affine state, so calls
 * from different host threads (even when serialized by a lock) can corrupt
 * the instance (see the jc2pa TileSigner for the rationale).  Concurrent
 * callers are queued and their results awaited.
 *
 * <p><b>Important:</b> the ultrahdr codec keeps process-global state — only
 * ONE live WASM instance of this module may exist per JVM (instances of the
 * c2pa and ultrahdr modules coexist fine; duplicates of the same module do
 * not).
 */
public final class GainMapCodec implements AutoCloseable {

    /**
     * Process-wide shared codec instance.
     *
     * <p>The ultrahdr codec keeps process-global state — only ONE live
     * instance of the WASM module may exist per JVM.  The pipeline classes
     * ({@link UltraHdrImageSource}, {@link UltraHdrTileSink}) share this
     * instance; it is never closed by them (JVM-lifetime).
     */
    private static volatile GainMapCodec sharedInstance;

    /** The WASM module instance backing this codec. */
    private final UltraHdrWasm wasm;

    /** Whether this service owns (and must close) the WASM instance. */
    private final boolean ownsWasm;

    /** Dedicated thread for all WASM access. */
    private final ExecutorService wasmExecutor;

    private volatile boolean closed = false;

    /**
     * Returns the process-wide shared codec, creating it on first use with
     * the given engine selection (ignored on subsequent calls).
     *
     * @param engineSelection {@code auto}, {@code chicory}, {@code graalvm},
     *                        or {@code null} for the {@code wasm.engine}
     *                        system property / {@code auto}.
     * @return The shared codec instance.
     * @throws IOException if the WASM module cannot be loaded.
     */
    public static synchronized GainMapCodec shared(String engineSelection) throws IOException {
        if (sharedInstance == null) {
            sharedInstance = new GainMapCodec(engineSelection);
        }
        return sharedInstance;
    }

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Create a codec that owns its WASM instance, using the engine selected
     * by {@code engineSelection}.
     *
     * @param engineSelection {@code auto}, {@code chicory}, {@code graalvm},
     *                        or {@code null} for the {@code wasm.engine}
     *                        system property / {@code auto}.
     * @throws IOException if the WASM module cannot be loaded.
     */
    public GainMapCodec(String engineSelection) throws IOException {
        this.wasm = new UltraHdrWasm(UltraHdrWasm.fromClasspathBytes(), engineSelection);
        this.ownsWasm = true;
        this.wasmExecutor = newExecutor();
    }

    /**
     * Create a codec sharing an existing WASM instance.  The caller keeps
     * ownership of the instance.
     *
     * @param wasm Shared {@link UltraHdrWasm} instance.
     */
    public GainMapCodec(UltraHdrWasm wasm) {
        this.wasm = wasm;
        this.ownsWasm = false;
        this.wasmExecutor = newExecutor();
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ultrahdr-codec");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Results ───────────────────────────────────────────────────────────────

    /**
     * The container-level split of an UltraHDR JPEG.
     *
     * @param primaryJpeg  Primary (SDR) image JPEG bytes.
     * @param gainmapJpeg  Gain map image JPEG bytes.
     * @param metadataJson ISO 21496-1 gain map metadata as JSON (camelCase).
     */
    public record UhdrSplit(byte[] primaryJpeg, byte[] gainmapJpeg, String metadataJson) {
    }

    // ── API ───────────────────────────────────────────────────────────────────

    /**
     * Split an UltraHDR JPEG into primary image, gain map and metadata.
     *
     * @param uhdrJpeg UltraHDR JPEG bytes.
     * @return The split parts.
     * @throws UltraHdrException     on WASM error.
     * @throws IllegalStateException if this codec has been closed.
     */
    public UhdrSplit decode(byte[] uhdrJpeg) throws UltraHdrException {
        return submit(() -> {
            WasmMemory mem = wasm.memory();

            int dataPtr    = mem.allocBytes(uhdrJpeg);
            int dataLen    = uhdrJpeg.length;
            int errPtrSlot = mem.allocPtrSlot();
            int errLenSlot = mem.allocU32Slot();

            int handle;
            try {
                handle = wasm.uhdrDecode(dataPtr, dataLen, errPtrSlot, errLenSlot);
            } finally {
                wasm.engine().free(dataPtr, dataLen);
            }

            // checkError frees the error slots of the call it inspects —
            // every WASM call therefore allocates FRESH slots (reusing a
            // freed slot is a double free that corrupts the module heap).
            checkError(handle, errPtrSlot, errLenSlot);

            try {
                byte[] primary  = readBlob(wasm::uhdrDecodePrimary, handle);
                byte[] gainmap  = readBlob(wasm::uhdrDecodeGainmap, handle);
                String metadata = readString(wasm::uhdrDecodeMetadata, handle);
                return new UhdrSplit(primary, gainmap, metadata);
            } finally {
                wasm.uhdrDecodeFree(handle);
            }
        });
    }

    /**
     * Assemble an UltraHDR JPEG from a primary JPEG, a gain map JPEG and
     * gain map metadata.
     *
     * @param primaryJpeg     Primary (SDR) tile JPEG bytes.
     * @param gainmapJpeg     Gain map tile JPEG bytes.
     * @param metadataJson    ISO 21496-1 gain map metadata JSON (verbatim
     *                        from the source image).
     * @param baseQuality     JPEG quality (1–100) for the primary image.
     * @param gainmapQuality  JPEG quality (1–100) for the gain map.
     * @return UltraHDR JPEG bytes.
     * @throws UltraHdrException     on WASM error.
     * @throws IllegalStateException if this codec has been closed.
     */
    public byte[] encode(byte[] primaryJpeg, byte[] gainmapJpeg, String metadataJson,
                         int baseQuality, int gainmapQuality) throws UltraHdrException {
        return submit(() -> {
            WasmMemory mem = wasm.memory();

            int primaryPtr  = mem.allocBytes(primaryJpeg);
            int primaryLen  = primaryJpeg.length;
            int gainmapPtr  = mem.allocBytes(gainmapJpeg);
            int gainmapLen  = gainmapJpeg.length;
            int metadataPtr = mem.allocString(metadataJson);
            int metadataLen = metadataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            int outLenSlot  = mem.allocU32Slot();
            int errPtrSlot  = mem.allocPtrSlot();
            int errLenSlot  = mem.allocU32Slot();

            int resultPtr;
            try {
                resultPtr = wasm.uhdrEncode(
                    primaryPtr, primaryLen,
                    gainmapPtr, gainmapLen,
                    metadataPtr, metadataLen,
                    baseQuality, gainmapQuality,
                    outLenSlot, errPtrSlot, errLenSlot);
            } finally {
                wasm.engine().free(primaryPtr, primaryLen);
                wasm.engine().free(gainmapPtr, gainmapLen);
                wasm.engine().free(metadataPtr, metadataLen);
            }

            checkError(resultPtr, errPtrSlot, errLenSlot);

            int len    = mem.readU32(outLenSlot);
            byte[] out = mem.readBytes(resultPtr, len);
            wasm.engine().free(resultPtr, len);
            wasm.engine().free(outLenSlot, 4);
            return out;
        });
    }

    /**
     * @return The ultrahdr codec version embedded in the WASM module.
     * @throws UltraHdrException     on WASM error.
     * @throws IllegalStateException if this codec has been closed.
     */
    public String version() throws UltraHdrException {
        return submit(() -> {
            WasmMemory mem = wasm.memory();
            int outLenSlot = mem.allocU32Slot();
            int ptr        = wasm.uhdrVersion(outLenSlot);
            int len        = mem.readU32(outLenSlot);
            String version = mem.readString(ptr, len);
            wasm.engine().free(ptr, len);
            wasm.engine().free(outLenSlot, 4);
            return version;
        });
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface BlobFn {
        int call(int handle, int outLenSlot, int errPtr, int errLen);
    }

    /** Reads a blob (JPEG bytes) from a decode handle. */
    private byte[] readBlob(BlobFn fn, int handle) {
        WasmMemory mem = wasm.memory();
        int outLenSlot = mem.allocU32Slot();
        int errPtrSlot = mem.allocPtrSlot();
        int errLenSlot = mem.allocU32Slot();
        int resultPtr  = fn.call(handle, outLenSlot, errPtrSlot, errLenSlot);
        checkError(resultPtr, errPtrSlot, errLenSlot);
        int len    = mem.readU32(outLenSlot);
        byte[] out = mem.readBytes(resultPtr, len);
        wasm.engine().free(resultPtr, len);
        wasm.engine().free(outLenSlot, 4);
        return out;
    }

    /** Reads a UTF-8 string from a decode handle. */
    private String readString(BlobFn fn, int handle) {
        WasmMemory mem = wasm.memory();
        int outLenSlot = mem.allocU32Slot();
        int errPtrSlot = mem.allocPtrSlot();
        int errLenSlot = mem.allocU32Slot();
        int resultPtr  = fn.call(handle, outLenSlot, errPtrSlot, errLenSlot);
        checkError(resultPtr, errPtrSlot, errLenSlot);
        int len    = mem.readU32(outLenSlot);
        String out = mem.readString(resultPtr, len);
        wasm.engine().free(resultPtr, len);
        wasm.engine().free(outLenSlot, 4);
        return out;
    }

    /** The {@code 0 = failure} convention check shared by all exports. */
    private void checkError(int result, int errPtrSlot, int errLenSlot) {
        WasmMemory mem = wasm.memory();
        if (result == 0) {
            int errBufPtr = mem.readPtr(errPtrSlot);
            int errBufLen = mem.readU32(errLenSlot);
            String message = "unknown ultrahdr error";
            if (errBufPtr != 0 && errBufLen > 0) {
                message = mem.readString(errBufPtr, errBufLen);
                wasm.engine().free(errBufPtr, errBufLen);
            }
            wasm.engine().free(errPtrSlot, 4);
            wasm.engine().free(errLenSlot, 4);
            throw new UltraHdrException(message);
        }
        wasm.engine().free(errPtrSlot, 4);
        wasm.engine().free(errLenSlot, 4);
    }

    /** Submit a WASM operation to the dedicated codec thread and await it. */
    private <T> T submit(Callable<T> operation) throws UltraHdrException {
        if (closed) {
            throw new IllegalStateException("GainMapCodec has been closed");
        }
        Future<T> future = wasmExecutor.submit(operation);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UltraHdrException("Codec call was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UltraHdrException uhdrCause) {
                throw uhdrCause;
            }
            if (cause instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            throw new UltraHdrException("Codec call failed", cause);
        }
    }

    @Override
    public void close() {
        if (this == sharedInstance) {
            // The shared instance lives for the JVM's lifetime — close() on
            // it is a no-op (callers must not shut it down).
            return;
        }
        if (closed) {
            return;
        }
        closed = true;
        wasmExecutor.shutdown();
        if (ownsWasm) {
            wasm.close();
        }
    }
}
