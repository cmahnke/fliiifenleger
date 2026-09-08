// src/main/java/de/christianmahnke/jc2pa/C2paWasm.java
package de.christianmahnke.jc2pa;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine;
import de.christianmahnke.iiif.fliiifenleger.wasm.WasmMemory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Low-level binding to the {@code c2pa_wasm} WASM module.
 *
 * <p>Each public method corresponds directly to one
 * {@code #[no_mangle] pub extern "C"} export from {@code lib.rs}.
 *
 * <p>The module is executed by a pluggable {@link WasmEngine}: by default
 * the pure-JVM Chicory interpreter (works on every JVM), with an automatic
 * switch to GraalVM's WebAssembly runtime when running on a GraalVM that has
 * the polyglot artifacts on the classpath.  See {@link WasmEngine#create}.
 *
 * <p>Type mapping between Rust and Java:
 * <pre>
 *   Rust          Java
 *   ──────────── ────────────────────────────────────────────
 *   *const u8    int   (WASM linear memory address)
 *   *mut u8      int   (WASM linear memory address)
 *   *mut *mut u8 int   (address of a 4-byte pointer slot)
 *   *mut u32     int   (address of a 4-byte u32 slot)
 *   u32          int   (sign-extended; use &amp; 0xFFFFFFFFL if unsigned needed)
 *   i32          int
 * </pre>
 *
 * <p>Use {@link WasmMemory} to read and write the raw bytes behind any
 * pointer that is returned or passed in.
 *
 * <p>This class is {@link Closeable}; always call {@link #close()} (or use
 * try-with-resources) to release the Chicory instance.
 */
public class C2paWasm implements Closeable {

    /** The WASM runtime engine executing the module. */
    private final WasmEngine engine;

    private final WasmMemory   memory;

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Load the WASM module from a file on the local file system.
     *
     * @param wasmPath Path to {@code c2pa_wasm.wasm}.
     * @throws IOException if the file cannot be read or the module is invalid.
     */
    public C2paWasm(Path wasmPath) throws IOException {
        this(Files.readAllBytes(wasmPath));
    }

    /**
     * Load the WASM module from a raw byte array (e.g. read from a JAR
     * resource with {@link Class#getResourceAsStream}).
     *
     * @param wasmBytes Raw WASM binary bytes.
     * @throws IOException if the module is invalid.
     */
    public C2paWasm(byte[] wasmBytes) throws IOException {
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
    public C2paWasm(byte[] wasmBytes, String engineSelection) throws IOException {
        this.engine = WasmEngine.create(engineSelection, wasmBytes);
        this.memory = new WasmMemory(engine);
    }

    /**
     * Convenience factory: load the WASM module from the classpath.
     *
     * <p>Checks the resource {@code /c2pa_wasm.wasm} first, then
     * {@code /wasm/c2pa_wasm.wasm} (the location used by the Maven build).
     *
     * @return A ready-to-use {@link C2paWasm} instance.
     * @throws IOException if the resource is not found or cannot be read.
     */
    public static C2paWasm fromClasspath() throws IOException {
        return new C2paWasm(fromClasspathBytes());
    }

    /**
     * Read the raw WASM module bytes from the classpath.
     *
     * <p>Checks the resource {@code /c2pa_wasm.wasm} first, then
     * {@code /wasm/c2pa_wasm.wasm} (the location used by the Maven build).
     *
     * @return Raw WASM binary bytes.
     * @throws IOException if the resource is not found or cannot be read.
     */
    public static byte[] fromClasspathBytes() throws IOException {
        for (String path : new String[]{"/c2pa_wasm.wasm", "/wasm/c2pa_wasm.wasm"}) {
            try (InputStream is = C2paWasm.class.getResourceAsStream(path)) {
                if (is != null) {
                    return is.readAllBytes();
                }
            }
        }
        throw new IOException(
            "WASM resource not found on classpath: /c2pa_wasm.wasm");
    }

    // ── Private construction helpers ──────────────────────────────────────────

    /** Invoke an export and return the single i32/u32 result. */
    private int call(String name, long... args) {
        return engine.callExport(name, args);
    }

    // ── Package-private accessor ──────────────────────────────────────────────

    /** Returns the {@link WasmMemory} helper bound to this module's memory. */
    WasmMemory memory() {
        return memory;
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

    // ── Reader exports ────────────────────────────────────────────────────────

    /**
     * {@code reader_from_bytes(format_ptr, format_len, data_ptr, data_len,
     *                          err_ptr, err_len) -> i32}
     *
     * @return Opaque reader handle, or {@code 0} on failure (check errPtr).
     */
    public int readerFromBytes(
            int formatPtr, int formatLen,
            int dataPtr,   int dataLen,
            int errPtr,    int errLen) {
        return call("reader_from_bytes",
                    formatPtr, formatLen,
                    dataPtr,   dataLen,
                    errPtr,    errLen);
    }

    /**
     * {@code reader_free(handle: i32)}
     *
     * <p>Free a reader handle.  Safe to call with {@code 0}.
     */
    public void readerFree(int handle) {
        if (handle != 0) {
            engine.execExport("reader_free", handle);
        }
    }

    /**
     * {@code reader_active_label(handle, out_len) -> *mut u8}
     *
     * @param handle     Reader handle.
     * @param outLenSlot WASM address of a {@code u32} slot; receives the
     *                   byte length of the returned string.
     * @return Pointer to label bytes (host must free), or {@code 0} if
     *         there is no active label.
     */
    public int readerActiveLabel(int handle, int outLenSlot) {
        return call("reader_active_label", handle, outLenSlot);
    }

    /**
     * {@code reader_json(handle, out_len, err_ptr, err_len) -> *mut u8}
     *
     * @return Pointer to JSON bytes (host must free), or {@code 0} on failure.
     */
    public int readerJson(
            int handle, int outLenSlot, int errPtr, int errLen) {
        return call("reader_json", handle, outLenSlot, errPtr, errLen);
    }

    /**
     * {@code reader_active_manifest_json(handle, out_len, err_ptr, err_len)
     *         -> *mut u8}
     *
     * @return Pointer to JSON bytes (host must free), or {@code 0} on failure.
     */
    public int readerActiveManifestJson(
            int handle, int outLenSlot, int errPtr, int errLen) {
        return call("reader_active_manifest_json",
                    handle, outLenSlot, errPtr, errLen);
    }

    /**
     * {@code reader_validation_results(handle, out_len, err_ptr, err_len)
     *         -> *mut u8}
     *
     * @return Pointer to JSON bytes (host must free), or {@code 0} when the
     *         store has no validation results (check errPtr for real errors).
     */
    public int readerValidationResults(
            int handle, int outLenSlot, int errPtr, int errLen) {
        return call("reader_validation_results", handle, outLenSlot, errPtr, errLen);
    }

    /**
     * {@code reader_validation_state(handle, out_len, err_ptr, err_len)
     *         -> *mut u8}
     *
     * @return Pointer to JSON bytes (host must free), or {@code 0} on failure.
     */
    public int readerValidationState(
            int handle, int outLenSlot, int errPtr, int errLen) {
        return call("reader_validation_state", handle, outLenSlot, errPtr, errLen);
    }

    // ── Builder exports ───────────────────────────────────────────────────────

    /**
     * {@code builder_from_json(json_ptr, json_len, err_ptr, err_len) -> i32}
     *
     * @return Opaque builder handle, or {@code 0} on failure (check errPtr).
     */
    public int builderFromJson(
            int jsonPtr, int jsonLen,
            int errPtr,  int errLen) {
        return call("builder_from_json", jsonPtr, jsonLen, errPtr, errLen);
    }

    /**
     * {@code builder_free(handle: i32)}
     *
     * <p>Free a builder handle.  Safe to call with {@code 0}.
     */
    public void builderFree(int handle) {
        if (handle != 0) {
            engine.execExport("builder_free", handle);
        }
    }

    /**
     * {@code builder_set_intent(builder_handle, intent_ptr, intent_len,
     *                           err_ptr, err_len) -> i32}
     *
     * @return {@code 1} on success, {@code 0} on failure (check errPtr).
     */
    public int builderSetIntent(
            int builderHandle,
            int intentPtr, int intentLen,
            int errPtr,    int errLen) {
        return call("builder_set_intent",
                    builderHandle, intentPtr, intentLen, errPtr, errLen);
    }

    /**
     * {@code builder_add_ingredient(builder_handle, ingredient_json_ptr,
     *                              ingredient_json_len, format_ptr, format_len,
     *                              data_ptr, data_len, err_ptr, err_len) -> i32}
     *
     * @return {@code 1} on success, {@code 0} on failure (check errPtr).
     */
    public int builderAddIngredient(
            int builderHandle,
            int ingredientJsonPtr, int ingredientJsonLen,
            int formatPtr,         int formatLen,
            int dataPtr,           int dataLen,
            int errPtr,            int errLen) {
        return call("builder_add_ingredient",
                    builderHandle,
                    ingredientJsonPtr, ingredientJsonLen,
                    formatPtr,         formatLen,
                    dataPtr,           dataLen,
                    errPtr,            errLen);
    }

    /**
     * {@code builder_sign_with_keys(builder_handle,
     *                               format_ptr, format_len,
     *                               asset_ptr, asset_len,
     *                               cert_ptr, cert_len,
     *                               key_ptr, key_len,
     *                               alg_ptr, alg_len,
     *                               tsa_ptr, tsa_len,
     *                               out_len, err_ptr, err_len) -> *mut u8}
     *
     * <p>Signs inside WASM using the PEM certificate chain and PEM private
     * key supplied by the host.
     *
     * @return Pointer to signed asset bytes (host must free), or {@code 0}
     *         on failure.  On success the builder handle is consumed.
     */
    public int builderSignWithKeys(
            int builderHandle,
            int formatPtr,  int formatLen,
            int assetPtr,   int assetLen,
            int certPtr,    int certLen,
            int keyPtr,     int keyLen,
            int algPtr,     int algLen,
            int tsaPtr,     int tsaLen,
            int outLenSlot,
            int errPtr,     int errLen) {
        return call("builder_sign_with_keys",
                    builderHandle,
                    formatPtr,  formatLen,
                    assetPtr,   assetLen,
                    certPtr,    certLen,
                    keyPtr,     keyLen,
                    algPtr,     algLen,
                    tsaPtr,     tsaLen,
                    outLenSlot,
                    errPtr,     errLen);
    }

    /**
     * {@code builder_sign_ephemeral(builder_handle,
     *                               format_ptr, format_len,
     *                               asset_ptr, asset_len,
     *                               cert_name_ptr, cert_name_len,
     *                               out_len, err_ptr, err_len) -> *mut u8}
     *
     * <p>Signs inside WASM using an ephemeral self-signed certificate chain.
     * Intended for tests and demos only.
     *
     * @return Pointer to signed asset bytes (host must free), or {@code 0}
     *         on failure.  On success the builder handle is consumed.
     */
    public int builderSignEphemeral(
            int builderHandle,
            int formatPtr,   int formatLen,
            int assetPtr,    int assetLen,
            int certNamePtr, int certNameLen,
            int outLenSlot,
            int errPtr,      int errLen) {
        return call("builder_sign_ephemeral",
                    builderHandle,
                    formatPtr,   formatLen,
                    assetPtr,    assetLen,
                    certNamePtr, certNameLen,
                    outLenSlot,
                    errPtr,      errLen);
    }

    // ── Utility exports ───────────────────────────────────────────────────────

    /**
     * {@code c2pa_version(out_len) -> *mut u8}
     *
     * <p>Returns the c2pa crate version string embedded in the WASM module.
     * Corresponds to the {@code c2pa::VERSION} constant in {@code lib.rs}.
     *
     * @param outLenSlot WASM address of a {@code u32} slot; receives byte
     *                   length of the returned string.
     * @return Pointer to UTF-8 version string bytes (host must free).
     */
    public int c2paVersion(int outLenSlot) {
        return call("c2pa_version", outLenSlot);
    }

    // ── Closeable ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        engine.close();
    }
}
