// src/test/java/de/christianmahnke/jc2pa/C2paWasmTest.java
package de.christianmahnke.jc2pa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link C2paWasm} — module loading and low-level exports.
 */
@DisplayName("C2paWasm")
class C2paWasmTest extends AbstractWasmTest {

    // ── Module loading ────────────────────────────────────────────────────────

    @Test
    @DisplayName("WASM module loads successfully")
    void wasmModuleLoads() {
        assertThat(wasm).isNotNull();
    }

    @Test
    @DisplayName("memory() returns a non-null WasmMemory")
    void memoryIsNonNull() {
        assertThat(wasm.memory()).isNotNull();
    }

    // ── wasm_alloc / wasm_free ────────────────────────────────────────────────

    @Test
    @DisplayName("wasmAlloc returns a non-zero address")
    void wasmAllocReturnsNonZero() {
        int ptr = wasm.wasmAlloc(16);
        assertThat(ptr).isNotZero();
        wasm.wasmFree(ptr, 16);
    }

    @Test
    @DisplayName("wasmAlloc of size 1 returns a non-zero address")
    void wasmAllocMinimumSize() {
        int ptr = wasm.wasmAlloc(1);
        assertThat(ptr).isNotZero();
        wasm.wasmFree(ptr, 1);
    }

    @Test
    @DisplayName("wasmAlloc of size 0 does not crash (internally allocates 1)")
    void wasmAllocZeroSizeDoesNotCrash() {
        // lib.rs: if size == 0, calls wasm_alloc(1) recursively.
        int ptr = wasm.wasmAlloc(0);
        assertThat(ptr).isNotZero();
        wasm.wasmFree(ptr, 1);
    }

    @Test
    @DisplayName("wasmFree with null pointer (0) does not crash")
    void wasmFreeNullPointerDoesNotCrash() {
        // lib.rs: wasm_free returns early when ptr == 0.
        wasm.wasmFree(0, 16);
    }

    @Test
    @DisplayName("wasmFree with size 0 does not crash")
    void wasmFreeSizeZeroDoesNotCrash() {
        int ptr = wasm.wasmAlloc(4);
        // lib.rs: wasm_free returns early when size == 0.
        wasm.wasmFree(ptr, 0);
        // ptr is technically leaked here but this is a test for crash safety.
    }

    @Test
    @DisplayName("sequential alloc/free cycles do not crash")
    void sequentialAllocFreeCycles() {
        for (int i = 1; i <= 100; i++) {
            int ptr = wasm.wasmAlloc(i);
            assertThat(ptr).isNotZero();
            wasm.wasmFree(ptr, i);
        }
    }

    // ── c2pa_version ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("c2paVersion returns a non-empty semver string")
    void c2paVersionIsNonEmpty() {
        WasmMemory mem     = wasm.memory();
        int        outSlot = mem.allocU32Slot();
        int        ptr     = wasm.c2paVersion(outSlot);
        int        len     = mem.readU32(outSlot);

        assertThat(ptr).isNotZero();
        assertThat(len).isGreaterThan(0);

        String version = mem.readString(ptr, len);
        wasm.wasmFree(ptr, len);
        wasm.wasmFree(outSlot, 4);

        assertThat(version)
            .isNotBlank()
            // Semver: digits separated by dots, e.g. "0.84.0"
            .matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    @DisplayName("c2paVersion matches the expected crate version")
    void c2paVersionMatchesExpected() {
        WasmMemory mem     = wasm.memory();
        int        outSlot = mem.allocU32Slot();
        int        ptr     = wasm.c2paVersion(outSlot);
        int        len     = mem.readU32(outSlot);
        String     version = mem.readString(ptr, len);
        wasm.wasmFree(ptr, len);
        wasm.wasmFree(outSlot, 4);

        assertThat(version).startsWith("0.84");
    }
}