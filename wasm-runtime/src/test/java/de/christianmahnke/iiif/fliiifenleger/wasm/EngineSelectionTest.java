// src/test/java/de/christianmahnke/iiif/fliiifenleger/wasm/EngineSelectionTest.java
package de.christianmahnke.iiif.fliiifenleger.wasm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for the {@link WasmEngine} selection and the automatic switch
 * between the Chicory and GraalWasm runtimes.
 *
 * <p>The CI runs on a stock JVM (no GraalVM), so the automatic selection
 * must land on Chicory here — which also proves the library works on a
 * non-Graal VM.  When the GraalVM polyglot artifacts are present and a
 * GraalVM runtime is detected, {@code auto} prefers GraalWasm (covered by
 * an assumption-guarded test that simply skips elsewhere).
 */
@DisplayName("WasmEngine selection")
class EngineSelectionTest {

    @Test
    @DisplayName("explicit chicory selection loads the module")
    void explicitChicory() throws Exception {
        try (WasmEngine engine = WasmEngine.create(WasmEngine.CHICORY, WasmTestSupport.fixtureWasm())) {
            assertThat(engine.name()).isEqualTo(WasmEngine.CHICORY);
            assertThat(engine.isAvailable()).isTrue();
        }
    }

    @Test
    @DisplayName("auto selection on a stock JVM uses Chicory")
    void autoOnStockJvmUsesChicory() throws Exception {
        assumeTrue(System.getProperty("org.graalvm.version") == null,
                   "test targets stock JVMs");
        try (WasmEngine engine = WasmEngine.create("auto", WasmTestSupport.fixtureWasm())) {
            assertThat(engine.name()).isEqualTo(WasmEngine.CHICORY);
        }
    }

    @Test
    @DisplayName("auto selection prefers GraalWasm on a GraalVM")
    void autoOnGraalVmPrefersGraal() throws Exception {
        assumeTrue(System.getProperty("org.graalvm.version") != null,
                   "test targets GraalVM runtimes");
        assumeTrue(GraalWasmEngine.polyglotOnClasspath(),
                   "GraalVM polyglot artifacts must be on the classpath");
        try (WasmEngine engine = WasmEngine.create("auto", WasmTestSupport.fixtureWasm())) {
            assertThat(engine.name()).isEqualTo(WasmEngine.GRAALVM);
        }
    }

    @Test
    @DisplayName("explicit graalvm selection fails with a clear error when artifacts are absent")
    void explicitGraalvmWithoutArtifactsFails() throws Exception {
        assumeTrue(!GraalWasmEngine.polyglotOnClasspath(),
                   "only meaningful when the polyglot artifacts are absent");
        assertThatThrownBy(() -> WasmEngine.create(WasmEngine.GRAALVM, WasmTestSupport.fixtureWasm()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("GraalWasm engine failed to load the module");
    }

    @Test
    @DisplayName("unknown engine name is rejected")
    void unknownEngineRejected() throws Exception {
        assertThatThrownBy(() -> WasmEngine.create("wasmtime", WasmTestSupport.fixtureWasm()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("wasm.engine");
    }
}
