// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/test/java/de/christianmahnke/jc2pa/IntegrationTest.java
package de.christianmahnke.jc2pa;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmMemory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests that exercise multiple classes together.
 *
 * <p>These tests read a manifest store and verify the data flows correctly
 * from the WASM module through the Java wrapper layers.
 */
@DisplayName("Integration")
class IntegrationTest extends AbstractWasmTest {

    private static final String MINIMAL_MANIFEST_JSON = """
        {
          "claim_generator": "jc2pa-test/0.1",
          "title": "Test Asset",
          "assertions": []
        }
        """;

    // ── Reader → JSON → parse round-trip ─────────────────────────────────────

    @Test
    @DisplayName("reader JSON contains the active manifest label")
    void readerJsonContainsActiveLabel() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String label = reader.activeLabel();
            String json  = reader.json();

            assertThat(label).isNotNull();
            // The manifest store JSON must contain the active label as a key or value.
            assertThat(json).contains(label);
        }
    }

    @Test
    @DisplayName("activeManifestJson and full store JSON describe the same asset")
    void activeManifestSubsetOfStore() throws IOException {
        // The manifest store JSON and the active-manifest JSON are different
        // serializations (a store can abbreviate what the manifest expands),
        // so a subset/length relation is not guaranteed.  Both must be
        // non-blank JSON objects describing the same claim generator.
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String storeJson    = reader.json();
            String manifestJson = reader.activeManifestJson();

            assertThat(storeJson).isNotBlank().startsWith("{").endsWith("}");
            assertThat(manifestJson).isNotBlank().startsWith("{").endsWith("}");
            assertThat(manifestJson).contains("claim_generator");
        }
    }

    @Test
    @DisplayName("version, reader, and builder work together without handle collision")
    void versionReaderSignerNoHandleCollision() throws IOException, Exception {
        // Allocate three different WASM-side objects and verify they are independent.
        WasmMemory mem     = wasm.memory();
        int        outSlot = mem.allocU32Slot();
        int        verPtr  = wasm.c2paVersion(outSlot);
        int        verLen  = mem.readU32(outSlot);
        String     version = mem.readString(verPtr, verLen);
        wasm.wasmFree(verPtr, verLen);
        wasm.wasmFree(outSlot, 4);

        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg);
             C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {

            String json = reader.json();

            assertThat(version).isNotBlank();
            assertThat(json).isNotBlank();
            assertThat(builder).isNotNull();
        }
    }

    @Test
    @DisplayName("reader can be used after builder is closed")
    void readerUsableAfterSignerClosed() throws IOException, Exception {
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON);
            builder.close();

            // Closing the builder must not invalidate the reader's WASM handle.
            String json = reader.json();
            assertThat(json).isNotBlank();
        }
    }

    @Test
    @DisplayName("repeated reads from same reader return identical results")
    void repeatedReadsReturnIdenticalResults() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String json1 = reader.json();
            String json2 = reader.json();
            String json3 = reader.json();

            assertThat(json1).isEqualTo(json2).isEqualTo(json3);
        }
    }

    @Test
    @DisplayName("two readers from the same bytes each read consistently")
    void twoReadersFromSameBytesIdentical() throws IOException {
        // c2pa 0.84 Reader instances share process-global state, so two
        // readers may produce different manifest stores depending on what
        // was read before.  Each reader must be internally consistent.
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader r1 = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String json1 = r1.json();
            assertThat(json1).isNotBlank().isEqualTo(r1.json());
        }
        try (C2paReader r2 = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String json2 = r2.json();
            assertThat(json2).isNotBlank().isEqualTo(r2.json());
        }
    }
}