// src/test/java/de/christianmahnke/iiif/fliiifenleger/wasm/WasmTestSupport.java
package de.christianmahnke.iiif.fliiifenleger.wasm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared helpers for the wasm-runtime tests.
 */
final class WasmTestSupport {

    private WasmTestSupport() {
    }

    /** Loads the committed minimal test fixture module. */
    static byte[] fixtureWasm() throws IOException {
        try (var is = WasmTestSupport.class.getResourceAsStream("/wasm_test_fixture.wasm")) {
            if (is != null) {
                return is.readAllBytes();
            }
        }
        Path local = Paths.get("src/test/resources/wasm_test_fixture.wasm");
        if (Files.exists(local)) {
            return Files.readAllBytes(local);
        }
        throw new IOException("Test fixture wasm not found: wasm_test_fixture.wasm");
    }
}
