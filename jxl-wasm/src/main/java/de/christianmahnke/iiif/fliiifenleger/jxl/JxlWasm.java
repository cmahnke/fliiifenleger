// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/iiif/fliiifenleger/jxl/JxlWasm.java
package de.christianmahnke.iiif.fliiifenleger.jxl;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine;
import de.christianmahnke.iiif.fliiifenleger.wasm.WasmMemory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Low-level binding to the {@code jxl_wasm} WASM module.
 *
 * <p>Each public method corresponds to one {@code #[no_mangle] pub extern "C"}
 * export from the Rust wrapper crate.  The engine executes the module via the
 * shared {@link WasmEngine} layer (Chicory by default, GraalWasm on GraalVM —
 * the only engine fast enough for full-frame JXL decode, see
 * {@link JxlDecoder}).
 *
 * <p>Unlike the c2pa/ultrahdr codecs the JXL module is stateless across
 * calls, but WASM instances are still neither thread-safe nor allowed to hop
 * host threads — share instances only through {@link JxlDecoder}'s lane
 * pool, never directly.
 *
 * <p>This class is {@link Closeable}; always call {@link #close()} (or use
 * try-with-resources) to release the engine.
 */
public class JxlWasm implements Closeable {

    /** The WASM runtime engine executing the module. */
    private final WasmEngine engine;

    private final WasmMemory memory;

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Load the WASM module from a file on the local file system.
     *
     * @param wasmPath Path to {@code jxl_wasm.wasm}.
     * @throws IOException if the file cannot be read or the module is invalid.
     */
    public JxlWasm(Path wasmPath) throws IOException {
        this(Files.readAllBytes(wasmPath), null);
    }

    /**
     * Load the WASM module from a raw byte array with the engine selected by
     * the {@code wasm.engine} system property (or {@code auto}).
     *
     * @param wasmBytes Raw WASM binary bytes.
     * @throws IOException if the module cannot be loaded by any engine.
     */
    public JxlWasm(byte[] wasmBytes) throws IOException {
        this(wasmBytes, null);
    }

    /**
     * Load the WASM module from a raw byte array, using the engine selected
     * by {@code engineSelection} (see {@link WasmEngine#create}).
     *
     * @param wasmBytes       Raw WASM binary bytes.
     * @param engineSelection {@code auto}, {@code chicory}, {@code graalvm},
     *                        or {@code null} for the {@code wasm.engine}
     *                        system property / {@code auto}.
     * @throws IOException if the module cannot be loaded by any engine.
     */
    public JxlWasm(byte[] wasmBytes, String engineSelection) throws IOException {
        this.engine = WasmEngine.create(engineSelection, wasmBytes);
        this.memory = new WasmMemory(engine);
    }

    /**
     * Convenience factory: load the WASM module from the classpath.
     *
     * <p>Checks the resource {@code /jxl_wasm.wasm} first, then
     * {@code /wasm/jxl_wasm.wasm} (the location used by the Maven build).
     *
     * @return A ready-to-use {@link JxlWasm} instance.
     * @throws IOException if the resource is not found or cannot be read.
     */
    public static JxlWasm fromClasspath() throws IOException {
        return new JxlWasm(fromClasspathBytes());
    }

    /**
     * Read the raw WASM module bytes from the classpath.
     *
     * @return Raw WASM binary bytes.
     * @throws IOException if the resource is not found or cannot be read.
     */
    public static byte[] fromClasspathBytes() throws IOException {
        for (String path : new String[]{"/jxl_wasm.wasm", "/wasm/jxl_wasm.wasm"}) {
            try (InputStream is = JxlWasm.class.getResourceAsStream(path)) {
                if (is != null) {
                    return is.readAllBytes();
                }
            }
        }
        throw new IOException(
            "WASM resource not found on classpath: /jxl_wasm.wasm");
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** Returns the {@link WasmMemory} helper bound to this module's memory. */
    public WasmMemory memory() {
        return memory;
    }

    /** Invoke an export and return the single i32/u32 result. */
    int call(String name, long... args) {
        return engine.callExport(name, args);
    }

    /** Returns the underlying engine (for alloc/free via {@link WasmMemory}). */
    WasmEngine engine() {
        return engine;
    }

    // ── Decode export ─────────────────────────────────────────────────────────

    /**
     * {@code jxl_decode(data_ptr, data_len, out_len, out_w, out_h,
     *                  out_channels, err_ptr, err_len) -> ptr}
     *
     * @return Pointer to the interleaved 8-bit pixel buffer (host must
     *         free), or {@code 0} on failure (check errPtr).
     */
    public int jxlDecode(int dataPtr, int dataLen, int outLenSlot,
                         int outWSlot, int outHSlot, int outChannelsSlot,
                         int errPtr, int errLen) {
        return call("jxl_decode",
                    dataPtr, dataLen, outLenSlot,
                    outWSlot, outHSlot, outChannelsSlot,
                    errPtr, errLen);
    }

    // ── Utility exports ───────────────────────────────────────────────────────

    /**
     * {@code jxl_version(out_len) -> *mut u8}
     *
     * @param outLenSlot WASM address of a {@code u32} slot; receives byte
     *                   length of the returned string.
     * @return Pointer to version string bytes (host must free).
     */
    public int jxlVersion(int outLenSlot) {
        return call("jxl_version", outLenSlot);
    }

    // ── Closeable ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        engine.close();
    }
}
