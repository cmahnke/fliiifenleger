// src/test/java/de/christianmahnke/jc2pa/TestWasmSupport.java
package de.christianmahnke.jc2pa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Process-wide shared {@link C2paWasm} instance for tests.
 *
 * <p>c2pa-rs keeps process-global state, so only ONE live WASM module
 * instance may exist per JVM — a second instance corrupts the first.
 * All test classes must share the instance from here instead of creating
 * their own.
 */
final class TestWasmSupport {

    private static C2paWasm instance;

    private TestWasmSupport() {
    }

    /** Returns the shared WASM instance, creating it on first use. */
    static synchronized C2paWasm shared() throws IOException {
        if (instance == null) {
            instance = new C2paWasm(resolveWasmBytes(), WasmEngine.CHICORY);
        }
        return instance;
    }

    /** Returns {@code true} once the shared instance exists. */
    static synchronized boolean isCreated() {
        return instance != null;
    }

    static byte[] resolveWasmBytes() throws IOException {
        try {
            return C2paWasm.fromClasspathBytes();
        } catch (IOException e) {
            Path local = Paths.get("src/main/resources/wasm/c2pa_wasm.wasm");
            if (Files.exists(local)) {
                return Files.readAllBytes(local);
            }
            throw e;
        }
    }

    static byte[] fixture(String name) throws IOException {
        try (var is = TestWasmSupport.class.getResourceAsStream("/fixtures/" + name)) {
            if (is != null) {
                return is.readAllBytes();
            }
        }
        Path local = Paths.get("src/test/resources/fixtures", name);
        if (Files.exists(local)) {
            return Files.readAllBytes(local);
        }
        throw new IOException("Test fixture not found: " + name);
    }
}
