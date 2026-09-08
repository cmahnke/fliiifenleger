// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/test/java/de/christianmahnke/jc2pa/AbstractWasmTest.java
package de.christianmahnke.jc2pa;

import org.junit.jupiter.api.AfterAll;

import java.io.IOException;

/**
 * Base class for all jc2pa tests.
 *
 * <p>Uses the process-wide shared WASM instance from
 * {@link TestWasmSupport} (c2pa-rs keeps process-global state, so exactly
 * one live instance may exist per JVM).
 *
 * <p>The WASM module is resolved in priority order:
 * <ol>
 *   <li>Classpath resource {@code /wasm/c2pa_wasm.wasm}
 *       (placed there by the Maven build via the copy-wasm-binary execution).</li>
 *   <li>File-system path
 *       {@code src/main/resources/wasm/c2pa_wasm.wasm} relative to the
 *       project root (useful when running tests from an IDE before packaging).</li>
 * </ol>
 */
public abstract class AbstractWasmTest {

    /** Shared WASM instance — created once per JVM. */
    protected static C2paWasm wasm;

    static {
        try {
            wasm = TestWasmSupport.shared();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void keepSharedWasmOpen() {
        // Intentionally NOT closed: the shared instance must stay open for
        // the remaining test classes in this JVM.
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    /**
     * Load a test fixture by name from {@code src/test/resources/fixtures/}.
     *
     * @param name File name, e.g. {@code "success.jpg"}.
     * @return File contents as a byte array.
     */
    protected static byte[] fixture(String name) throws IOException {
        return TestWasmSupport.fixture(name);
    }
}
