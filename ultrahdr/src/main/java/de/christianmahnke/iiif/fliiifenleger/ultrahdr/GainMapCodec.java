// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/iiif/fliiifenleger/ultrahdr/GainMapCodec.java
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine;
import de.christianmahnke.iiif.fliiifenleger.wasm.WasmLanePool;
import de.christianmahnke.iiif.fliiifenleger.wasm.WasmMemory;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Splits UltraHDR JPEGs into primary/gain map parts and re-assembles tiles
 * with integrated gain maps.
 *
 * <p>WASM instances are neither thread-safe nor allowed to hop host threads,
 * so every lane of the internal pool pairs one interpreter instance with one
 * dedicated worker thread that lazily creates it and never shares it.
 * Separate instances are fully isolated (each owns its linear memory), which
 * is what makes parallel execution sound.  Tasks are routed round-robin
 * across lanes; concurrent callers block for their own result.
 *
 * <p>Parallelism defaults to one lane per available processor capped at 4
 * (opt out with parallelism {@code 1}); a single lane behaves exactly like
 * the previous dedicated codec thread.  Request more or fewer lanes with
 * {@link #GainMapCodec(String, int)} (or {@code --sink-opt threads=N} on the
 * {@code ultrahdr} sink); the shared instance from {@link #shared} and
 * externally shared {@link UltraHdrWasm} instances are always serial.
 *
 * <p><b>Important:</b> the shared instance is process-wide — pipeline classes
 * ({@link UltraHdrImageSource}, {@link UltraHdrTileSink}) share it; it is
 * never closed by them (JVM-lifetime).
 */
public final class GainMapCodec implements AutoCloseable {

    /**
     * Process-wide shared codec instance (serial, JVM-lifetime).
     *
     * <p>The pipeline classes ({@link UltraHdrImageSource},
     * {@link UltraHdrTileSink}) share this instance; it is never closed by
     * them (JVM-lifetime).
     */
    private static volatile GainMapCodec sharedInstance;

    /** Lane pool: one interpreter instance per worker thread. */
    private final WasmLanePool<UltraHdrWasm> lanes;

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
     * Create a codec that owns its WASM instance(s), using the engine selected
     * by {@code engineSelection}.  Uses a single lane; the sinks resolve the
     * core-based default separately.
     *
     * @param engineSelection {@code auto}, {@code chicory}, {@code graalvm},
     *                        or {@code null} for the {@code wasm.engine}
     *                        system property / {@code auto}.
     * @throws IOException if the WASM module cannot be loaded.
     */
    public GainMapCodec(String engineSelection) throws IOException {
        this(engineSelection, 1);
    }

    /**
     * Create a codec that owns its WASM instance(s), with the given lane
     * parallelism.  {@code 1} selects the classic serial behaviour; larger
     * values assemble on that many lanes in parallel.
     *
     * @param engineSelection {@code auto}, {@code chicory}, {@code graalvm},
     *                        or {@code null} for the {@code wasm.engine}
     *                        system property / {@code auto}.
     * @param parallelism     Requested lane count; clamped to the available
     *                        processors (and to 2 for GraalWasm).
     * @throws IOException if the WASM module cannot be loaded.
     * @throws IllegalArgumentException if {@code parallelism < 1}.
     */
    public GainMapCodec(String engineSelection, int parallelism) throws IOException {
        this(UltraHdrWasm.fromClasspathBytes(), engineSelection, parallelism, true);
    }

    /**
     * Create a codec sharing an existing WASM instance.  The caller keeps
     * ownership of the instance.  A shared instance is always serial
     * (parallelism 1).
     *
     * @param wasm Shared {@link UltraHdrWasm} instance.
     */
    public GainMapCodec(UltraHdrWasm wasm) {
        this(wasm, 1);
    }

    /**
     * Create a codec sharing an existing WASM instance.  The caller keeps
     * ownership of the instance.
     *
     * @param wasm Shared {@link UltraHdrWasm} instance.
     * @param parallelism Must be 1 — a shared instance cannot run on
     *                    parallel lanes.
     * @throws IllegalArgumentException if {@code parallelism != 1}.
     */
    GainMapCodec(UltraHdrWasm wasm, int parallelism) {
        if (parallelism != 1) {
            throw new IllegalArgumentException(
                "A shared UltraHdrWasm instance supports only parallelism 1, got " + parallelism);
        }
        final UltraHdrWasm shared = wasm;
        this.lanes = new WasmLanePool<>("ultrahdr-codec", 1, () -> shared, false);
    }

    private GainMapCodec(byte[] wasmBytes, String engineSelection, int parallelism, boolean owned)
            throws IOException {
        int lanes = WasmEngine.resolveParallelism(parallelism, engineSelection);
        this.lanes = new WasmLanePool<>("ultrahdr-codec", lanes,
            () -> new UltraHdrWasm(wasmBytes, engineSelection), owned);
        // Fail fast on unloadable modules, as the old constructor did.
        this.lanes.initEagerly();
    }

    /** @return The configured lane count (for tests). */
    int laneCount() {
        return lanes.laneCount();
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
        return submit(wasm -> {
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
            checkError(wasm, handle, errPtrSlot, errLenSlot);

            try {
                byte[] primary  = readBlob(wasm, wasm::uhdrDecodePrimary, handle);
                byte[] gainmap  = readBlob(wasm, wasm::uhdrDecodeGainmap, handle);
                String metadata = readString(wasm, wasm::uhdrDecodeMetadata, handle);
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
        return submit(wasm -> {
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

            checkError(wasm, resultPtr, errPtrSlot, errLenSlot);

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
        return submit(wasm -> {
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
    private byte[] readBlob(UltraHdrWasm wasm, BlobFn fn, int handle) {
        WasmMemory mem = wasm.memory();
        int outLenSlot = mem.allocU32Slot();
        int errPtrSlot = mem.allocPtrSlot();
        int errLenSlot = mem.allocU32Slot();
        int resultPtr  = fn.call(handle, outLenSlot, errPtrSlot, errLenSlot);
        checkError(wasm, resultPtr, errPtrSlot, errLenSlot);
        int len    = mem.readU32(outLenSlot);
        byte[] out = mem.readBytes(resultPtr, len);
        wasm.engine().free(resultPtr, len);
        wasm.engine().free(outLenSlot, 4);
        return out;
    }

    /** Reads a UTF-8 string from a decode handle. */
    private String readString(UltraHdrWasm wasm, BlobFn fn, int handle) {
        WasmMemory mem = wasm.memory();
        int outLenSlot = mem.allocU32Slot();
        int errPtrSlot = mem.allocPtrSlot();
        int errLenSlot = mem.allocU32Slot();
        int resultPtr  = fn.call(handle, outLenSlot, errPtrSlot, errLenSlot);
        checkError(wasm, resultPtr, errPtrSlot, errLenSlot);
        int len    = mem.readU32(outLenSlot);
        String out = mem.readString(resultPtr, len);
        wasm.engine().free(resultPtr, len);
        wasm.engine().free(outLenSlot, 4);
        return out;
    }

    /** The {@code 0 = failure} convention check shared by all exports. */
    private void checkError(UltraHdrWasm wasm, int result, int errPtrSlot, int errLenSlot) {
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

    /** Runs a codec operation on a pool lane with that lane's instance and awaits it. */
    private <T> T submit(Function<UltraHdrWasm, T> operation) throws UltraHdrException {
        if (closed) {
            throw new IllegalStateException("GainMapCodec has been closed");
        }
        Future<T> future = lanes.submit(operation::apply);
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
        lanes.close();
    }
}
