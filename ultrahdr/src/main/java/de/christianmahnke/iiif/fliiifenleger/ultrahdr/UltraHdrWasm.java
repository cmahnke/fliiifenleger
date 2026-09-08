// src/main/java/de/christianmahnke/iiif/fliiifenleger/ultrahdr/UltraHdrWasm.java
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine;
import de.christianmahnke.iiif.fliiifenleger.wasm.WasmMemory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Low-level binding to the {@code ultrahdr_wasm} WASM module.
 *
 * <p>Each public method corresponds to one {@code #[no_mangle] pub extern "C"}
 * export from the Rust wrapper crate.  The engine executes the module via the
 * shared {@link WasmEngine} layer (Chicory by default, GraalWasm on GraalVM).
 *
 * <p><b>Important:</b> the ultrahdr codec keeps process-global state — only
 * ONE {@code UltraHdrWasm} instance should be alive per JVM, and concurrent
 * use must be routed through a single thread (see {@link GainMapCodec}).
 *
 * <p>This class is {@link Closeable}; always call {@link #close()} (or use
 * try-with-resources) to release the engine.
 */
public class UltraHdrWasm implements Closeable {

    /** The WASM runtime engine executing the module. */
    private final WasmEngine engine;

    private final WasmMemory memory;

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Load the WASM module from a file on the local file system.
     *
     * @param wasmPath Path to {@code ultrahdr_wasm.wasm}.
     * @throws IOException if the file cannot be read or the module is invalid.
     */
    public UltraHdrWasm(Path wasmPath) throws IOException {
        this(Files.readAllBytes(wasmPath), null);
    }

    /**
     * Load the WASM module from a raw byte array with the engine selected by
     * the {@code wasm.engine} system property (or {@code auto}).
     *
     * @param wasmBytes Raw WASM binary bytes.
     * @throws IOException if the module cannot be loaded by any engine.
     */
    public UltraHdrWasm(byte[] wasmBytes) throws IOException {
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
    public UltraHdrWasm(byte[] wasmBytes, String engineSelection) throws IOException {
        this.engine = WasmEngine.create(engineSelection, wasmBytes);
        this.memory = new WasmMemory(engine);
    }

    /**
     * Convenience factory: load the WASM module from the classpath.
     *
     * <p>Checks the resource {@code /ultrahdr_wasm.wasm} first, then
     * {@code /wasm/ultrahdr_wasm.wasm} (the location used by the Maven build).
     *
     * @return A ready-to-use {@link UltraHdrWasm} instance.
     * @throws IOException if the resource is not found or cannot be read.
     */
    public static UltraHdrWasm fromClasspath() throws IOException {
        return new UltraHdrWasm(fromClasspathBytes());
    }

    /**
     * Read the raw WASM module bytes from the classpath.
     *
     * @return Raw WASM binary bytes.
     * @throws IOException if the resource is not found or cannot be read.
     */
    public static byte[] fromClasspathBytes() throws IOException {
        for (String path : new String[]{"/ultrahdr_wasm.wasm", "/wasm/ultrahdr_wasm.wasm"}) {
            try (InputStream is = UltraHdrWasm.class.getResourceAsStream(path)) {
                if (is != null) {
                    return is.readAllBytes();
                }
            }
        }
        throw new IOException(
            "WASM resource not found on classpath: /ultrahdr_wasm.wasm");
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

    // ── Utility exports ───────────────────────────────────────────────────────

    /**
     * {@code uhdr_version(out_len) -> *mut u8}
     *
     * @param outLenSlot WASM address of a {@code u32} slot; receives byte
     *                   length of the returned string.
     * @return Pointer to version string bytes (host must free).
     */
    public int uhdrVersion(int outLenSlot) {
        return call("uhdr_version", outLenSlot);
    }

    // ── Decode exports ────────────────────────────────────────────────────────

    /**
     * {@code uhdr_decode(data_ptr, data_len, err_ptr, err_len) -> handle}
     *
     * @return Opaque decode handle, or {@code 0} on failure (check errPtr).
     */
    public int uhdrDecode(int dataPtr, int dataLen, int errPtr, int errLen) {
        return call("uhdr_decode", dataPtr, dataLen, errPtr, errLen);
    }

    /**
     * {@code uhdr_decode_primary(handle, out_len, err_ptr, err_len) -> ptr}
     *
     * @return Pointer to primary JPEG bytes (host must free), or {@code 0}.
     */
    public int uhdrDecodePrimary(int handle, int outLenSlot, int errPtr, int errLen) {
        return call("uhdr_decode_primary", handle, outLenSlot, errPtr, errLen);
    }

    /**
     * {@code uhdr_decode_gainmap(handle, out_len, err_ptr, err_len) -> ptr}
     *
     * @return Pointer to gain map JPEG bytes (host must free), or {@code 0}.
     */
    public int uhdrDecodeGainmap(int handle, int outLenSlot, int errPtr, int errLen) {
        return call("uhdr_decode_gainmap", handle, outLenSlot, errPtr, errLen);
    }

    /**
     * {@code uhdr_decode_metadata(handle, out_len, err_ptr, err_len) -> ptr}
     *
     * @return Pointer to metadata JSON bytes (host must free), or {@code 0}.
     */
    public int uhdrDecodeMetadata(int handle, int outLenSlot, int errPtr, int errLen) {
        return call("uhdr_decode_metadata", handle, outLenSlot, errPtr, errLen);
    }

    /**
     * {@code uhdr_decode_free(handle)}
     */
    public void uhdrDecodeFree(int handle) {
        if (handle != 0) {
            engine.execExport("uhdr_decode_free", handle);
        }
    }

    // ── Encode export ─────────────────────────────────────────────────────────

    /**
     * {@code uhdr_encode(primary…, gainmap…, metadata…, base_quality,
     *                    gainmap_quality, out_len, err_ptr, err_len) -> ptr}
     *
     * @return Pointer to UHDR JPEG bytes (host must free), or {@code 0}.
     */
    public int uhdrEncode(
            int primaryPtr, int primaryLen,
            int gainmapPtr, int gainmapLen,
            int metadataPtr, int metadataLen,
            int baseQuality, int gainmapQuality,
            int outLenSlot, int errPtr, int errLen) {
        return call("uhdr_encode",
                    primaryPtr, primaryLen,
                    gainmapPtr, gainmapLen,
                    metadataPtr, metadataLen,
                    baseQuality, gainmapQuality,
                    outLenSlot, errPtr, errLen);
    }

    // ── Closeable ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        engine.close();
    }
}
