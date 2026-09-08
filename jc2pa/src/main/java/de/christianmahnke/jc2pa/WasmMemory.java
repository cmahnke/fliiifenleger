// src/main/java/de/christianmahnke/jc2pa/WasmMemory.java
package de.christianmahnke.jc2pa;

import java.nio.charset.StandardCharsets;

/**
 * Low-level helpers for reading and writing into WASM linear memory.
 *
 * <p>Backed by the {@link WasmEngine} abstraction, so it works with any
 * engine.  All addresses are plain Java {@code int} values (WASM is 32-bit).
 *
 * <p>Memory ownership contract (defined in {@code lib.rs}):
 * <ul>
 *   <li><b>Input buffers</b>  – allocated here via {@link #allocBytes} /
 *       {@link #allocString}, freed here after the WASM call returns.</li>
 *   <li><b>Output buffers</b> – allocated by WASM (Rust global allocator),
 *       ownership transferred to the caller on return.  The caller MUST
 *       free them with {@link C2paWasm#wasmFree(int, int)}.</li>
 *   <li><b>Out-parameter slots</b> – 4-byte regions allocated here to hold
 *       {@code u32} or pointer values written by WASM.  Always freed by
 *       the caller after reading.</li>
 * </ul>
 */
public final class WasmMemory {

    /** The engine providing linear-memory access. */
    private final WasmEngine engine;

    /** Reference back to the owning {@link C2paWasm} for alloc/free calls. */
    private final C2paWasm wasm;

    WasmMemory(WasmEngine engine, C2paWasm wasm) {
        this.engine = engine;
        this.wasm   = wasm;
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    /**
     * Read {@code length} bytes from WASM linear memory starting at
     * {@code address}.
     *
     * @param address WASM linear memory byte address.
     * @param length  Number of bytes to read.
     * @return A new Java {@code byte[]}.
     */
    public byte[] readBytes(int address, int length) {
        return engine.readBytes(address, length);
    }

    /**
     * Read a UTF-8 string from WASM linear memory.
     *
     * @param address WASM linear memory byte address.
     * @param length  Number of bytes to read.
     * @return Decoded Java {@link String}.
     */
    public String readString(int address, int length) {
        return engine.readString(address, length);
    }

    /**
     * Read a 32-bit little-endian unsigned integer from WASM memory.
     *
     * <p>Used to dereference {@code *mut u32} out-parameter slots after
     * a WASM call has written a length or size into them.
     *
     * @param address WASM linear memory byte address of the 4-byte slot.
     * @return The integer value (may be negative due to Java sign extension;
     *         use {@code value & 0xFFFFFFFFL} for unsigned arithmetic).
     */
    public int readU32(int address) {
        return engine.readU32(address);
    }

    /**
     * Read a 32-bit pointer value from WASM memory.
     *
     * <p>Used to dereference {@code *mut *mut u8} out-parameter slots after
     * a WASM call has written an error-string pointer into them.
     *
     * @param address WASM linear memory byte address of the 4-byte pointer slot.
     * @return The pointer value as a Java {@code int}.
     */
    public int readPtr(int address) {
        // In wasm32, pointers are 32-bit — same representation as u32.
        return readU32(address);
    }

    // ── Writing ───────────────────────────────────────────────────────────────

    /**
     * Write {@code data} into WASM linear memory starting at {@code address}.
     *
     * @param address WASM linear memory byte address.
     * @param data    Bytes to write.
     */
    public void writeBytes(int address, byte[] data) {
        engine.writeBytes(address, data);
    }

    /**
     * Write a 32-bit little-endian integer into WASM memory.
     *
     * <p>Used to zero-initialise out-parameter slots before passing them
     * to a WASM function.
     *
     * @param address WASM linear memory byte address of the 4-byte slot.
     * @param value   Value to write.
     */
    public void writeU32(int address, int value) {
        engine.writeU32(address, value);
    }

    // ── Allocation helpers ────────────────────────────────────────────────────

    /**
     * Copy a Java {@code byte[]} into WASM linear memory via
     * {@code wasm_alloc}, returning the WASM address.
     *
     * <p>The caller is responsible for freeing the buffer with
     * {@link C2paWasm#wasmFree(int, int)} when done.
     *
     * @param data Bytes to copy.
     * @return WASM address of the allocated buffer.
     */
    public int allocBytes(byte[] data) {
        int ptr = wasm.wasmAlloc(data.length);
        writeBytes(ptr, data);
        return ptr;
    }

    /**
     * Encode {@code s} as UTF-8, copy into WASM linear memory, and return
     * the WASM address.
     *
     * <p>The caller is responsible for freeing the buffer with
     * {@link C2paWasm#wasmFree(int, int)} when done, using the byte length
     * of the UTF-8 encoding (NOT the Java {@code String} length).
     *
     * @param s String to encode and copy.
     * @return WASM address of the allocated buffer.
     */
    public int allocString(String s) {
        return allocBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Allocate a zeroed 4-byte slot for a {@code u32} out-parameter.
     *
     * <p>Pass the returned address as a {@code *mut u32} argument to a WASM
     * function; read the result back with {@link #readU32(int)}; then free
     * with {@code wasm.wasmFree(slot, 4)}.
     *
     * @return WASM address of the 4-byte slot.
     */
    public int allocU32Slot() {
        int ptr = wasm.wasmAlloc(4);
        writeU32(ptr, 0);
        return ptr;
    }

    /**
     * Allocate a zeroed 4-byte slot for a {@code *mut u8} pointer
     * out-parameter.
     *
     * <p>In wasm32, a pointer is 4 bytes — identical to a {@code u32} slot.
     * Pass the returned address as a {@code *mut *mut u8} argument to a WASM
     * function; read the result back with {@link #readPtr(int)}; then free
     * with {@code wasm.wasmFree(slot, 4)}.
     *
     * @return WASM address of the 4-byte pointer slot.
     */
    public int allocPtrSlot() {
        // A pointer in wasm32 is 4 bytes — same as a u32 slot.
        return allocU32Slot();
    }
}
