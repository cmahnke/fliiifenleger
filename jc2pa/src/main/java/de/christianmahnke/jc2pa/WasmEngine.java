// src/main/java/de/christianmahnke/jc2pa/WasmEngine.java
package de.christianmahnke.jc2pa;

import java.io.Closeable;
import java.io.IOException;

/**
 * Abstraction over the WebAssembly runtime used to execute the
 * {@code c2pa_wasm} module.
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
 * {@code jc2pa.engine} system property ({@code auto}, {@code chicory}, or
 * {@code graalvm}).
 *
 * <p><b>Threading:</b> WASM execution is single-threaded in both engines.
 * Implementations must not be invoked from multiple threads without external
 * synchronisation.
 */
public abstract class WasmEngine implements Closeable {

    /** System property selecting the engine: {@code auto}, {@code chicory}, or {@code graalvm}. */
    public static final String ENGINE_PROPERTY = "jc2pa.engine";

    /** Engine name for the Chicory implementation. */
    public static final String CHICORY = "chicory";

    /** Engine name for the GraalVM implementation. */
    public static final String GRAALVM = "graalvm";

    // ── Engine selection ──────────────────────────────────────────────────────

    /**
     * Create an engine for the given WASM module bytes, honouring the
     * {@code jc2pa.engine} system property.
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
                    "Unknown jc2pa.engine value: " + chosen
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
     * {@code int} (the module's exports all produce i32/u32 or pointer
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

    /**
     * Release the engine's resources.  Narrowed to not throw — failures on
     * close must not mask the outcome of the operations performed before.
     */
    @Override
    public abstract void close();

    /**
     * @return {@code true} if this engine's runtime artifacts are present on
     *         the classpath.
     */
    public abstract boolean isAvailable();
}
