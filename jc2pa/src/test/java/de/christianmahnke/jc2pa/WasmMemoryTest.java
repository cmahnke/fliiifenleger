// src/test/java/de/christianmahnke/jc2pa/WasmMemoryTest.java
package de.christianmahnke.jc2pa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WasmMemory} read/write primitives.
 *
 * <p>Uses a real WASM module (via {@link AbstractWasmTest}) so that we test
 * against an actual GraalVM memory {@link org.graalvm.polyglot.Value}.
 */
@DisplayName("WasmMemory")
class WasmMemoryTest extends AbstractWasmTest {

    // ── Byte round-trips ──────────────────────────────────────────────────────

    @Test
    @DisplayName("allocBytes / readBytes round-trips arbitrary bytes")
    void bytesRoundTrip() {
        WasmMemory mem   = wasm.memory();
        byte[]     data  = {0x00, 0x01, 0x7F, (byte) 0x80, (byte) 0xFF};
        int        ptr   = mem.allocBytes(data);
        byte[]     read  = mem.readBytes(ptr, data.length);
        wasm.wasmFree(ptr, data.length);

        assertThat(read).isEqualTo(data);
    }

    @Test
    @DisplayName("allocBytes / readBytes handles empty array")
    void emptyBytesRoundTrip() {
        // wasm_alloc(0) calls wasm_alloc(1) internally — must not crash.
        WasmMemory mem  = wasm.memory();
        int        ptr  = mem.allocBytes(new byte[0]);
        byte[]     read = mem.readBytes(ptr, 0);
        wasm.wasmFree(ptr, 1); // at least 1 byte was allocated

        assertThat(read).isEmpty();
    }

    // ── String round-trips ────────────────────────────────────────────────────

    @Test
    @DisplayName("allocString / readString round-trips ASCII")
    void asciiStringRoundTrip() {
        WasmMemory mem    = wasm.memory();
        String     input  = "image/jpeg";
        int        ptr    = mem.allocString(input);
        int        len    = input.getBytes(StandardCharsets.UTF_8).length;
        String     result = mem.readString(ptr, len);
        wasm.wasmFree(ptr, len);

        assertThat(result).isEqualTo(input);
    }

    @Test
    @DisplayName("allocString / readString round-trips UTF-8 multibyte characters")
    void utf8StringRoundTrip() {
        WasmMemory mem   = wasm.memory();
        // 3-byte UTF-8 sequence: ✅ = U+2705
        String     input = "✅ c2pa ✅";
        byte[]     utf8  = input.getBytes(StandardCharsets.UTF_8);
        int        ptr   = mem.allocString(input);
        String     result = mem.readString(ptr, utf8.length);
        wasm.wasmFree(ptr, utf8.length);

        assertThat(result).isEqualTo(input);
    }

    // ── u32 round-trips ───────────────────────────────────────────────────────

    @Test
    @DisplayName("allocU32Slot / writeU32 / readU32 round-trips zero")
    void u32ZeroRoundTrip() {
        WasmMemory mem  = wasm.memory();
        int        slot = mem.allocU32Slot();
        int        val  = mem.readU32(slot);
        wasm.wasmFree(slot, 4);

        // allocU32Slot initialises to 0.
        assertThat(val).isZero();
    }

    @Test
    @DisplayName("writeU32 / readU32 round-trips max u32 value")
    void u32MaxRoundTrip() {
        WasmMemory mem   = wasm.memory();
        int        slot  = mem.allocU32Slot();
        // 0xFFFFFFFF = -1 in Java signed int, but the bits are preserved.
        mem.writeU32(slot, 0xFFFFFFFF);
        int result = mem.readU32(slot);
        wasm.wasmFree(slot, 4);

        assertThat(result).isEqualTo(0xFFFFFFFF);
    }

    @Test
    @DisplayName("writeU32 / readU32 round-trips a typical length value")
    void u32TypicalLengthRoundTrip() {
        WasmMemory mem   = wasm.memory();
        int        slot  = mem.allocU32Slot();
        mem.writeU32(slot, 12345);
        int result = mem.readU32(slot);
        wasm.wasmFree(slot, 4);

        assertThat(result).isEqualTo(12345);
    }

    // ── Pointer slot ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("allocPtrSlot initialises to null pointer (0)")
    void ptrSlotInitialisedToNull() {
        WasmMemory mem  = wasm.memory();
        int        slot = mem.allocPtrSlot();
        int        ptr  = mem.readPtr(slot);
        wasm.wasmFree(slot, 4);

        assertThat(ptr).isZero();
    }

    // ── Multiple independent allocations ─────────────────────────────────────

    @Test
    @DisplayName("two independent allocations do not overlap")
    void twoAllocationsDoNotOverlap() {
        WasmMemory mem    = wasm.memory();
        byte[]     data1  = "hello".getBytes(StandardCharsets.UTF_8);
        byte[]     data2  = "world".getBytes(StandardCharsets.UTF_8);

        int ptr1 = mem.allocBytes(data1);
        int ptr2 = mem.allocBytes(data2);

        // Addresses must be distinct and non-overlapping.
        assertThat(Math.abs(ptr2 - ptr1)).isGreaterThanOrEqualTo(data1.length);

        String read1 = mem.readString(ptr1, data1.length);
        String read2 = mem.readString(ptr2, data2.length);

        wasm.wasmFree(ptr1, data1.length);
        wasm.wasmFree(ptr2, data2.length);

        assertThat(read1).isEqualTo("hello");
        assertThat(read2).isEqualTo("world");
    }
}