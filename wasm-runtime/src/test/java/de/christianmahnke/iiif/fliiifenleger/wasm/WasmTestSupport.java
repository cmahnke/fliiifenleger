// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/test/java/de/christianmahnke/iiif/fliiifenleger/wasm/WasmTestSupport.java
package de.christianmahnke.iiif.fliiifenleger.wasm;

/**
 * Shared helpers for the wasm-runtime tests.
 */
final class WasmTestSupport {

    private WasmTestSupport() {
    }

    /** Generates the minimal test fixture module in-memory (see {@link FixtureModule}). */
    static byte[] fixtureWasm() {
        return FixtureModule.bytes();
    }
}
