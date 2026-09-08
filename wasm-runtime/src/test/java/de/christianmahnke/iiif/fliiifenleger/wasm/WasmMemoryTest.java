// src/test/java/de/christianmahnke/iiif/fliiifenleger/wasm/WasmMemoryTest.java
package de.christianmahnke.iiif.fliiifenleger.wasm;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WasmMemory} and the module memory contract
 * ({@code wasm_alloc}/{@code wasm_free}) on the {@link ChicoryEngine}.
 */
@DisplayName("WasmMemory")
class WasmMemoryTest {

    private static WasmEngine engine;
    private static WasmMemory mem;

    @BeforeAll
    static void loadFixture() throws Exception {
        engine = new ChicoryEngine(WasmTestSupport.fixtureWasm());
        mem = new WasmMemory(engine);
    }

    @AfterAll
    static void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    // ── alloc / free ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("alloc returns a non-zero zeroed buffer")
    void allocZeroed() {
        int ptr = engine.alloc(16);
        assertThat(ptr).isNotZero();
        byte[] data = mem.readBytes(ptr, 16);
        assertThat(data).containsOnly((byte) 0);
        engine.free(ptr, 16);
    }

    @Test
    @DisplayName("alloc of size 0 allocates one byte (module contract)")
    void allocZeroSize() {
        int ptr = engine.alloc(0);
        assertThat(ptr).isNotZero();
        engine.free(ptr, 1);
    }

    @Test
    @DisplayName("sequential alloc/free cycles do not corrupt memory")
    void sequentialCycles() {
        for (int i = 1; i <= 50; i++) {
            int ptr = engine.alloc(i);
            assertThat(ptr).isNotZero();
            engine.free(ptr, i);
        }
    }

    @Test
    @DisplayName("free with null pointer or zero size is a no-op")
    void freeNoOps() {
        engine.free(0, 16);
        int ptr = engine.alloc(4);
        engine.free(ptr, 0);
        engine.free(ptr, 4);
    }

    // ── read/write helpers ────────────────────────────────────────────────────

    @Test
    @DisplayName("writeBytes/readBytes round-trip")
    void bytesRoundTrip() {
        byte[] data = {1, 2, 3, 4, 5};
        int ptr = mem.allocBytes(data);
        assertThat(mem.readBytes(ptr, data.length)).isEqualTo(data);
        engine.free(ptr, data.length);
    }

    @Test
    @DisplayName("allocString/readString round-trip (UTF-8)")
    void stringRoundTrip() {
        String s = "wäsche-grün-✓";
        int ptr = mem.allocString(s);
        // The buffer length is the UTF-8 byte count, not the char count.
        int len = s.getBytes(StandardCharsets.UTF_8).length;
        assertThat(mem.readString(ptr, len)).isEqualTo(s);
        engine.free(ptr, len);
    }

    @Test
    @DisplayName("u32 slots: writeU32/readU32 little-endian round-trip")
    void u32Slots() {
        int slot = mem.allocU32Slot();
        mem.writeU32(slot, 0x01020304);
        assertThat(mem.readU32(slot)).isEqualTo(0x01020304);
        assertThat(mem.readPtr(slot)).isEqualTo(0x01020304);
        engine.free(slot, 4);
    }

    @Test
    @DisplayName("pointer slots are zero-initialised")
    void pointerSlotsZeroed() {
        int slot = mem.allocPtrSlot();
        assertThat(mem.readU32(slot)).isZero();
        engine.free(slot, 4);
    }

    // ── module exports through the engine ─────────────────────────────────────

    @Test
    @DisplayName("module version export round-trips through out-parameter slots")
    void versionExport() {
        int outSlot = mem.allocU32Slot();
        int ptr = engine.callExport("test_version", outSlot);
        int len = mem.readU32(outSlot);
        String version = mem.readString(ptr, len);
        engine.free(ptr, len);
        engine.free(outSlot, 4);
        assertThat(version).isEqualTo("1.2.3");
    }
}
