// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/iiif/fliiifenleger/wasm/WasmModule.java
package de.christianmahnke.iiif.fliiifenleger.wasm;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared base for the low-level bindings to the codec WASM modules
 * ({@code c2pa_wasm}, {@code ultrahdr_wasm}, {@code jxl_wasm}).
 *
 * <p>Every codec binding duplicates the same plumbing: loading the module
 * from a file, a raw byte array or the classpath, creating the
 * {@link WasmEngine} and the bound {@link WasmMemory}, and releasing the
 * engine on {@link #close()}.  That boilerplate lives here; concrete
 * subclasses only declare their {@code #[no_mangle] pub extern "C"} export
 * methods (e.g. {@code uhdr_decode}, {@code jxl_decode}) and the accessors
 * they expose with their desired visibility.
 *
 * <p>Each module is executed by a pluggable {@link WasmEngine}: by default
 * the pure-JVM Chicory interpreter (works on every JVM), with an automatic
 * switch to GraalVM's WebAssembly runtime when running on a GraalVM that has
 * the polyglot artifacts on the classpath.  See {@link WasmEngine#create}.
 *
 * <p>This class is {@link Closeable}; always call {@link #close()} (or use
 * try-with-resources) to release the engine.
 */
public abstract class WasmModule implements Closeable {

    /** The WASM runtime engine executing the module. */
    protected final WasmEngine engine;

    /** Memory helper bound to the engine's linear memory. */
    protected final WasmMemory memory;

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Load the WASM module from a file on the local file system.
     *
     * @param wasmPath Path to the {@code *.wasm} file.
     * @throws IOException if the file cannot be read or the module is invalid.
     */
    protected WasmModule(Path wasmPath) throws IOException {
        this(Files.readAllBytes(wasmPath), null);
    }

    /**
     * Load the WASM module from a raw byte array with the engine selected by
     * the {@code wasm.engine} system property (or {@code auto}).
     *
     * @param wasmBytes Raw WASM binary bytes.
     * @throws IOException if the module cannot be loaded by any engine.
     */
    protected WasmModule(byte[] wasmBytes) throws IOException {
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
    @SuppressWarnings("this-escape") // WasmMemory only stores the reference
    protected WasmModule(byte[] wasmBytes, String engineSelection) throws IOException {
        this.engine = WasmEngine.create(engineSelection, wasmBytes);
        this.memory = new WasmMemory(engine);
    }

    // ── Classpath loading ─────────────────────────────────────────────────────

    /**
     * Read the raw WASM module bytes from the classpath.
     *
     * <p>Checks the resource {@code /<name>} first, then
     * {@code /wasm/<name>} (the location used by the Maven build).
     *
     * @param resourceName The wasm file name, e.g. {@code "c2pa_wasm.wasm"}.
     * @return Raw WASM binary bytes.
     * @throws IOException if the resource is not found or cannot be read.
     */
    protected static byte[] fromClasspathBytes(String resourceName) throws IOException {
        for (String path : new String[]{"/" + resourceName, "/wasm/" + resourceName}) {
            try (InputStream is = WasmModule.class.getResourceAsStream(path)) {
                if (is != null) {
                    return is.readAllBytes();
                }
            }
        }
        throw new IOException("WASM resource not found on classpath: /" + resourceName);
    }

    // ── Memory management exports ─────────────────────────────────────────────

    /**
     * {@code wasm_alloc(size: u32) -> *mut u8}
     *
     * <p>Allocate {@code size} zeroed bytes in WASM linear memory.
     * The caller MUST eventually free the result with {@link #wasmFree}.
     *
     * @param size Number of bytes to allocate (must be &gt; 0).
     * @return WASM linear memory address of the allocated buffer.
     */
    public int wasmAlloc(int size) {
        return engine.alloc(size);
    }

    /**
     * {@code wasm_free(ptr: *mut u8, size: u32)}
     *
     * <p>Free memory previously allocated by {@link #wasmAlloc} or returned
     * as an output buffer by any function in this module.
     *
     * @param ptr  WASM linear memory address to free (ignored if 0).
     * @param size Number of bytes that were allocated (ignored if 0).
     */
    public void wasmFree(int ptr, int size) {
        engine.free(ptr, size);
    }

    // ── Closeable ─────────────────────────────────────────────────────────────

    /**
     * Release the engine's resources.  Narrowed to not throw — failures on
     * close must not mask the outcome of the operations performed before.
     */
    @Override
    public void close() {
        engine.close();
    }
}
