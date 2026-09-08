// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/iiif/fliiifenleger/wasm/WasmEngine.java
package de.christianmahnke.iiif.fliiifenleger.wasm;

import java.io.Closeable;
import java.io.IOException;

/**
 * Abstraction over the WebAssembly runtime used to execute the compiled
 * codec modules (c2pa, ultrahdr, …).
 *
 * <p>Two implementations exist:
 * <ul>
 *   <li>{@link ChicoryEngine} — pure-JVM interpreter, zero native
 *       dependencies, works on any JVM (default).</li>
 *   <li>{@link GraalWasmEngine} — GraalVM's Truffle-based WebAssembly
 *       runtime; only usable when the GraalVM polyglot artifacts are on the
 *       classpath and faster on GraalVM (JIT) than on a stock JVM.</li>
 * </ul>
 *
 * <p>The engine is chosen automatically by {@link #create(String, byte[])}
 * (see there for the selection rules) and can be pinned with the
 * {@code wasm.engine} system property ({@code auto}, {@code chicory}, or
 * {@code graalvm}).
 *
 * <p><b>Module memory contract:</b> every module executed through this
 * engine must export {@code wasm_alloc(size) -> ptr} and
 * {@code wasm_free(ptr, size)} — the standard ownership convention used by
 * all fliiifenleger codec crates (see the crate READMEs).  {@link
 * #allocBytes}, {@link #allocString} and friends build on these exports.
 *
 * <p><b>Threading:</b> WASM execution is single-threaded in both engines,
 * and codec libraries keep process-global state — route all access through
 * one dedicated thread (see the TileSigner in the jc2pa module for the
 * pattern) and never create two live instances of the same module per JVM.
 */
public abstract class WasmEngine implements Closeable {

    /** System property selecting the engine: {@code auto}, {@code chicory}, or {@code graalvm}. */
    public static final String ENGINE_PROPERTY = "wasm.engine";

    /** Engine name for the Chicory implementation. */
    public static final String CHICORY = "chicory";

    /** Engine name for the GraalVM implementation. */
    public static final String GRAALVM = "graalvm";

    // ── Engine selection ──────────────────────────────────────────────────────

    /**
     * Create an engine for the given WASM module bytes, honouring the
     * {@code wasm.engine} system property.
     *
     * <p>Selection rules:
     * <ul>
     *   <li>{@code chicory} — always use Chicory (default for a pinned
     *       selection).</li>
     *   <li>{@code graalvm} — use GraalWasm; requires the GraalVM polyglot
     *       artifacts on the classpath.</li>
     *   <li>{@code auto} (default) — on a GraalVM runtime, prefer GraalWasm
     *       when its artifacts are present; otherwise use Chicory.  If the
     *       preferred engine fails to load the module, fall back to Chicory
     *       (which works on every JVM).</li>
     * </ul>
     *
     * @param selection One of {@code auto}, {@code chicory}, {@code graalvm},
     *                  or {@code null} for {@code auto}.
     * @param wasmBytes Raw WASM module bytes.
     * @return A ready-to-use engine with the module loaded.
     * @throws IOException if no engine could load the module.
     */
    public static WasmEngine create(String selection, byte[] wasmBytes)
            throws IOException {
        String chosen = selection != null ? selection
                                          : System.getProperty(ENGINE_PROPERTY, "auto");

        switch (chosen) {
            case CHICORY:
                return new ChicoryEngine(wasmBytes);
            case GRAALVM:
                return createGraalOrFallback(wasmBytes, false);
            case "auto":
                return createAuto(wasmBytes);
            default:
                throw new IllegalArgumentException(
                    "Unknown wasm.engine value: " + chosen
                    + " (expected auto, chicory, or graalvm)");
        }
    }

    /**
     * Automatic selection: prefer GraalWasm only when running on a GraalVM
     * runtime with its polyglot artifacts present; otherwise (and on any
     * load failure) use Chicory.
     */
    private static WasmEngine createAuto(byte[] wasmBytes) throws IOException {
        boolean onGraalVm = System.getProperty("org.graalvm.version") != null;
        if (onGraalVm && GraalWasmEngine.polyglotOnClasspath()) {
            return createGraalOrFallback(wasmBytes, true);
        }
        return new ChicoryEngine(wasmBytes);
    }

    /**
     * Create the GraalWasm engine; fall back to Chicory when allowed
     * ({@code auto} mode) or rethrow as {@link IOException} when explicitly
     * requested.
     */
    private static WasmEngine createGraalOrFallback(byte[] wasmBytes,
                                                    boolean allowFallback)
            throws IOException {
        try {
            return new GraalWasmEngine(wasmBytes);
        } catch (IOException | RuntimeException e) {
            if (!allowFallback) {
                throw new IOException(
                    "GraalWasm engine failed to load the module: " + e.getMessage(), e);
            }
            // Automatic switch: any other JVM — or a broken Graal setup —
            // runs on Chicory.
            return new ChicoryEngine(wasmBytes);
        }
    }

    // ── Contract ──────────────────────────────────────────────────────────────

    /**
     * @return The engine name ({@code chicory} or {@code graalvm}).
     */
    public abstract String name();

    /**
     * Invoke an exported function and return its first result as an
     * {@code int} (the modules' exports all produce i32/u32 or pointer
     * values).
     *
     * @param name Export name, e.g. {@code "wasm_alloc"}.
     * @param args Arguments mapped to i32 values.
     * @return First result value.
     */
    public abstract int callExport(String name, long... args);

    /**
     * Invoke an exported function, discarding the result.
     *
     * @param name Export name.
     * @param args Arguments mapped to i32 values.
     */
    public abstract void execExport(String name, long... args);

    /** Read {@code length} bytes from linear memory at {@code address}. */
    public abstract byte[] readBytes(int address, int length);

    /** Read a UTF-8 string of {@code length} bytes at {@code address}. */
    public abstract String readString(int address, int length);

    /** Read a 32-bit little-endian value at {@code address}. */
    public abstract int readU32(int address);

    /** Write {@code data} into linear memory at {@code address}. */
    public abstract void writeBytes(int address, byte[] data);

    /** Write a 32-bit little-endian value at {@code address}. */
    public abstract void writeU32(int address, int value);

    // ── Module memory contract (wasm_alloc / wasm_free) ──────────────────────

    /**
     * Allocate {@code size} zeroed bytes in the module's linear memory via
     * the {@code wasm_alloc} export.
     *
     * @param size Number of bytes to allocate (must be &gt; 0).
     * @return WASM linear memory address of the allocated buffer.
     */
    public int alloc(int size) {
        return callExport("wasm_alloc", size);
    }

    /**
     * Free memory previously allocated by {@link #alloc} or returned as an
     * output buffer by a module function, via the {@code wasm_free} export.
     *
     * @param ptr  WASM linear memory address to free (ignored if 0).
     * @param size Number of bytes that were allocated (ignored if 0).
     */
    public void free(int ptr, int size) {
        if (ptr != 0 && size > 0) {
            execExport("wasm_free", ptr, size);
        }
    }

    /**
     * @return {@code true} if this engine's runtime artifacts are present on
     *         the classpath.
     */
    public abstract boolean isAvailable();

    /**
     * Release the engine's resources.  Narrowed to not throw — failures on
     * close must not mask the outcome of the operations performed before.
     */
    @Override
    public abstract void close();
}
