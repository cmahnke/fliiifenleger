// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/iiif/fliiifenleger/jxl/JxlDecoder.java
package de.christianmahnke.iiif.fliiifenleger.jxl;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine;
import de.christianmahnke.iiif.fliiifenleger.wasm.WasmLanePool;
import de.christianmahnke.iiif.fliiifenleger.wasm.WasmMemory;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Decodes JPEG XL images to raw pixels through the {@code jxl_wasm} module.
 *
 * <p>WASM instances are neither thread-safe nor allowed to hop host threads,
 * so every lane of the internal pool pairs one interpreter instance with one
 * dedicated worker thread that lazily creates it and never shares it.
 * Separate instances are fully isolated (each owns its linear memory), which
 * is what makes parallel execution sound.  The underlying module is
 * stateless across calls, so unlike the c2pa/ultrahdr codecs there is no
 * shared-instance restriction beyond the usual lane affinity.
 *
 * <p>Performance note (measured September 2026, jxl-oxide 0.12): the Chicory
 * interpreter needs ~26 s for a 512x512 image and over 10 minutes for a
 * 34 MP photo, while GraalWasm on a GraalVM decodes the same inputs in
 * ~0.2 s and ~10 s.  This module is therefore intended for the future
 * GraalVM-native build only — pass {@code "graalvm"} (or run with
 * {@code -Dwasm.engine=graalvm} on a GraalVM) and keep the NightMonkeys
 * {@code imageio-jxl} plugin as the default JXL path on regular JVMs.
 *
 * <p>Color note: the module renders without an external CMS, matching the
 * reference decoder within ±1 LSB (wasm32 has no FMA unit).
 */
public final class JxlDecoder implements AutoCloseable {

    /** Lane pool: one interpreter instance per worker thread. */
    private final WasmLanePool<JxlWasm> lanes;

    private volatile boolean closed = false;

    /**
     * Create a decoder that owns its WASM instance(s), using the engine
     * selected by {@code engineSelection}.  Uses a single lane.
     *
     * @param engineSelection {@code auto}, {@code chicory}, {@code graalvm},
     *                        or {@code null} for the {@code wasm.engine}
     *                        system property / {@code auto}.
     * @throws IOException if the WASM module cannot be loaded.
     */
    public JxlDecoder(String engineSelection) throws IOException {
        this(engineSelection, 1);
    }

    /**
     * Create a decoder that owns its WASM instance(s), with the given lane
     * parallelism.  {@code 1} selects serial behaviour.
     *
     * @param engineSelection {@code auto}, {@code chicory}, {@code graalvm},
     *                        or {@code null} for the {@code wasm.engine}
     *                        system property / {@code auto}.
     * @param parallelism     Requested lane count; clamped to the available
     *                        processors (and to 2 for GraalWasm).
     * @throws IOException if the WASM module cannot be loaded.
     * @throws IllegalArgumentException if {@code parallelism < 1}.
     */
    public JxlDecoder(String engineSelection, int parallelism) throws IOException {
        this(JxlWasm.fromClasspathBytes(), engineSelection, parallelism, true);
    }

    /**
     * Create a decoder sharing an existing WASM instance.  The caller keeps
     * ownership of the instance.  A shared instance is always serial
     * (parallelism 1).
     *
     * @param wasm Shared {@link JxlWasm} instance.
     */
    public JxlDecoder(JxlWasm wasm) {
        this(wasm, 1);
    }

    /**
     * Create a decoder sharing an existing WASM instance.  The caller keeps
     * ownership of the instance.
     *
     * @param wasm Shared {@link JxlWasm} instance.
     * @param parallelism Must be 1 — a shared instance cannot run on
     *                    parallel lanes.
     * @throws IllegalArgumentException if {@code parallelism != 1}.
     */
    JxlDecoder(JxlWasm wasm, int parallelism) {
        if (parallelism != 1) {
            throw new IllegalArgumentException(
                "A shared JxlWasm instance supports only parallelism 1, got " + parallelism);
        }
        final JxlWasm shared = wasm;
        this.lanes = new WasmLanePool<>("jxl-decoder", 1, () -> shared, false);
    }

    private JxlDecoder(byte[] wasmBytes, String engineSelection, int parallelism, boolean owned)
            throws IOException {
        int lanes = WasmEngine.resolveParallelism(parallelism, engineSelection);
        this.lanes = new WasmLanePool<>("jxl-decoder", lanes,
            () -> new JxlWasm(wasmBytes, engineSelection), owned);
        // Fail fast on unloadable modules.
        this.lanes.initEagerly();
    }

    /** @return The configured lane count (for tests). */
    int laneCount() {
        return lanes.laneCount();
    }

    // ── Results ───────────────────────────────────────────────────────────────

    /**
     * A decoded JPEG XL frame: interleaved 8-bit samples
     * ({@code width * height * channels} bytes, e.g. 3 for RGB).
     */
    public record DecodedImage(int width, int height, int channels, byte[] pixels) {
    }

    // ── API ───────────────────────────────────────────────────────────────────

    /**
     * Decode JPEG XL bytes to raw pixels.
     *
     * @param jxlBytes JXL codestream bytes.
     * @return Dimensions, channel count and interleaved 8-bit pixels.
     * @throws JxlWasmException    on WASM error (corrupt input, implausible
     *                             dimensions).
     * @throws IllegalStateException if this decoder has been closed.
     */
    public DecodedImage decode(byte[] jxlBytes) throws JxlWasmException {
        return submit(wasm -> {
            WasmMemory mem = wasm.memory();

            int dataPtr       = mem.allocBytes(jxlBytes);
            int dataLen       = jxlBytes.length;
            int outLenSlot    = mem.allocU32Slot();
            int outWSlot      = mem.allocU32Slot();
            int outHSlot      = mem.allocU32Slot();
            int outChanSlot   = mem.allocU32Slot();
            int errPtrSlot    = mem.allocPtrSlot();
            int errLenSlot    = mem.allocU32Slot();

            int resultPtr;
            try {
                resultPtr = wasm.jxlDecode(
                    dataPtr, dataLen, outLenSlot,
                    outWSlot, outHSlot, outChanSlot,
                    errPtrSlot, errLenSlot);
            } finally {
                wasm.engine().free(dataPtr, dataLen);
            }

            // checkError frees the error slots of the call it inspects —
            // every WASM call therefore allocates FRESH slots (reusing a
            // freed slot is a double free that corrupts the module heap).
            checkError(wasm, resultPtr, errPtrSlot, errLenSlot);

            int len      = mem.readU32(outLenSlot);
            int width    = mem.readU32(outWSlot);
            int height   = mem.readU32(outHSlot);
            int channels = mem.readU32(outChanSlot);
            if (width <= 0 || height <= 0 || channels <= 0 || len <= 0) {
                wasm.engine().free(resultPtr, len);
                wasm.engine().free(outLenSlot, 4);
                wasm.engine().free(outWSlot, 4);
                wasm.engine().free(outHSlot, 4);
                wasm.engine().free(outChanSlot, 4);
                throw new JxlWasmException(
                    "Decoder returned implausible dimensions: "
                    + width + "x" + height + "x" + channels);
            }
            byte[] pixels = mem.readBytes(resultPtr, len);
            wasm.engine().free(resultPtr, len);
            wasm.engine().free(outLenSlot, 4);
            wasm.engine().free(outWSlot, 4);
            wasm.engine().free(outHSlot, 4);
            wasm.engine().free(outChanSlot, 4);
            return new DecodedImage(width, height, channels, pixels);
        });
    }

    /**
     * @return The jxl-oxide codec version embedded in the WASM module.
     * @throws JxlWasmException    on WASM error.
     * @throws IllegalStateException if this decoder has been closed.
     */
    public String codecVersion() throws JxlWasmException {
        return submit(wasm -> {
            WasmMemory mem = wasm.memory();
            int outLenSlot = mem.allocU32Slot();
            int ptr        = wasm.jxlVersion(outLenSlot);
            int len        = mem.readU32(outLenSlot);
            String version = mem.readString(ptr, len);
            wasm.engine().free(ptr, len);
            wasm.engine().free(outLenSlot, 4);
            return version;
        });
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** The {@code 0 = failure} convention check shared by all exports. */
    private void checkError(JxlWasm wasm, int result, int errPtrSlot, int errLenSlot) {
        WasmMemory mem = wasm.memory();
        if (result == 0) {
            int errBufPtr = mem.readPtr(errPtrSlot);
            int errBufLen = mem.readU32(errLenSlot);
            String message = "unknown jxl-wasm error";
            if (errBufPtr != 0 && errBufLen > 0) {
                message = mem.readString(errBufPtr, errBufLen);
                wasm.engine().free(errBufPtr, errBufLen);
            }
            wasm.engine().free(errPtrSlot, 4);
            wasm.engine().free(errLenSlot, 4);
            throw new JxlWasmException(message);
        }
        wasm.engine().free(errPtrSlot, 4);
        wasm.engine().free(errLenSlot, 4);
    }

    /** Runs a decoder operation on a pool lane with that lane's instance and awaits it. */
    private <T> T submit(Function<JxlWasm, T> operation) throws JxlWasmException {
        if (closed) {
            throw new IllegalStateException("JxlDecoder has been closed");
        }
        Future<T> future = lanes.submit(operation::apply);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JxlWasmException("Decoder call was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof JxlWasmException jxlCause) {
                throw jxlCause;
            }
            if (cause instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            throw new JxlWasmException("Decoder call failed", cause);
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
}
