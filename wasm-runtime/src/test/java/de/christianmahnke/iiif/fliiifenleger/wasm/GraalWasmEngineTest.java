// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/test/java/de/christianmahnke/iiif/fliiifenleger/wasm/GraalWasmEngineTest.java
package de.christianmahnke.iiif.fliiifenleger.wasm;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the {@link GraalWasmEngine} end to end against the in-memory
 * fixture module: instantiation, export calls, memory reads/writes and
 * freeing.
 *
 * <p>Runs wherever the GraalVM polyglot artifacts are on the classpath
 * (including stock JVMs in CI — Truffle executes without the Graal JIT,
 * just slower); it skips only when the artifacts are absent.  This guards
 * the polyglot instantiation contract ({@code eval} yields a module object
 * that must be explicitly instantiated — a regression here surfaces as
 * an NPE on the first export call, which selection-only tests miss).
 *
 * <p>Note: unlike {@link EngineSelectionTest}, this test deliberately does
 * <em>not</em> require {@code org.graalvm.version} — modern GraalVM
 * distributions no longer set that property, so gating on it would mean
 * the test never runs anywhere.
 */
@DisplayName("GraalWasmEngine")
class GraalWasmEngineTest {

    private static WasmEngine engine;
    private static WasmMemory mem;

    @BeforeAll
    static void loadFixture() throws Exception {
        assumeTrue(GraalWasmEngine.polyglotOnClasspath(),
                   "GraalVM polyglot artifacts must be on the classpath");
        engine = WasmEngine.create(WasmEngine.GRAALVM, WasmTestSupport.fixtureWasm());
        mem = new WasmMemory(engine);
    }

    @AfterAll
    static void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @DisplayName("engine reports the graalvm name")
    void engineName() {
        assertThat(engine.name()).isEqualTo(WasmEngine.GRAALVM);
    }

    @Test
    @DisplayName("alloc/write/read/free round-trip preserves bytes")
    void memoryRoundTrip() {
        byte[] payload = "graalvm-roundtrip".getBytes(StandardCharsets.UTF_8);
        int ptr = mem.allocBytes(payload);
        try {
            assertThat(ptr).isNotZero();
            assertThat(mem.readBytes(ptr, payload.length)).isEqualTo(payload);
        } finally {
            engine.free(ptr, payload.length);
        }
    }

    @Test
    @DisplayName("u32 slot write/read round-trip")
    void u32RoundTrip() {
        int slot = mem.allocU32Slot();
        try {
            mem.writeU32(slot, 0xDEADBEEF);
            assertThat(mem.readU32(slot)).isEqualTo(0xDEADBEEF);
        } finally {
            engine.free(slot, 4);
        }
    }

    @Test
    @DisplayName("export call returns the fixture version string")
    void exportCall() {
        int lenSlot = mem.allocU32Slot();
        try {
            int ptr = engine.callExport("test_version", lenSlot);
            try {
                int len = mem.readU32(lenSlot);
                assertThat(ptr).isNotZero();
                assertThat(mem.readString(ptr, len)).isEqualTo(FixtureModule.VERSION);
            } finally {
                engine.free(ptr, mem.readU32(lenSlot));
            }
        } finally {
            engine.free(lenSlot, 4);
        }
    }

    @Test
    @DisplayName("void export executes without error")
    void voidExport() {
        int ptr = engine.alloc(8);
        engine.execExport("wasm_free", ptr, 8);
    }
}
