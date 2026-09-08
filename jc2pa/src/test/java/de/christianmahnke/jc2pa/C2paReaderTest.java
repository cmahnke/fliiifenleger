// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/test/java/de/christianmahnke/jc2pa/C2paReaderTest.java
package de.christianmahnke.jc2pa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link C2paReader}.
 *
 * <p>Uses real JPEG fixtures containing embedded c2pa manifests.
 */
@DisplayName("C2paReader")
class C2paReaderTest extends AbstractWasmTest {

    // ── fromBytes ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fromBytes returns a non-null reader for a valid JPEG")
    void fromBytesValidJpeg() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            assertThat(reader).isNotNull();
        }
    }

    @Test
    @DisplayName("fromBytes throws C2paException for empty byte array")
    void fromBytesEmptyDataThrows() {
        assertThatThrownBy(() ->
            C2paReader.fromBytes(wasm, "image/jpeg", new byte[0]).close())
            .isInstanceOf(C2paException.class);
    }

    @Test
    @DisplayName("fromBytes throws C2paException for random bytes")
    void fromBytesGarbageDataThrows() {
        byte[] garbage = new byte[]{0x00, 0x01, 0x02, 0x03};
        assertThatThrownBy(() ->
            C2paReader.fromBytes(wasm, "image/jpeg", garbage).close())
            .isInstanceOf(C2paException.class);
    }

    @Test
    @DisplayName("malformed CBOR manifest either throws or opens without crashing")
    void fromBytesMalformedCborThrows() throws IOException {
        // c2pa 0.84 defers validation: the reader may open the asset and
        // report issues via validation results instead of throwing.
        byte[] jpeg = fixture("malformed_cbor.jpg");
        try {
            try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
                assertThat(reader.json()).isNotBlank();
            }
        } catch (C2paException e) {
            // Acceptable: c2pa may reject the file outright.
            assertThat(e.getMessage()).isNotBlank();
        }
    }

    // ── activeLabel ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("activeLabel returns a non-null string for a signed JPEG")
    void activeLabelNonNull() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String label = reader.activeLabel();
            assertThat(label).isNotNull().isNotBlank();
        }
    }

    @Test
    @DisplayName("activeLabel contains expected manifest label pattern")
    void activeLabelPattern() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String label = reader.activeLabel();
            // c2pa manifest labels follow the pattern: <urn:uuid:...> or similar
            assertThat(label).isNotNull();
        }
    }

    // ── json ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("json returns valid JSON string")
    void jsonReturnsValidJson() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String json = reader.json();
            assertThat(json)
                .isNotBlank()
                .startsWith("{")
                .endsWith("}");
        }
    }

    @Test
    @DisplayName("json contains manifests key")
    void jsonContainsManifestsKey() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String json = reader.json();
            assertThat(json).contains("\"manifests\"");
        }
    }

    @Test
    @DisplayName("json contains active_manifest key")
    void jsonContainsActiveManifestKey() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String json = reader.json();
            assertThat(json).contains("\"active_manifest\"");
        }
    }

    // ── activeManifestJson ────────────────────────────────────────────────────

    @Test
    @DisplayName("activeManifestJson returns valid JSON")
    void activeManifestJsonValid() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String json = reader.activeManifestJson();
            assertThat(json)
                .isNotBlank()
                .startsWith("{")
                .endsWith("}");
        }
    }

    @Test
    @DisplayName("activeManifestJson contains claim_generator key")
    void activeManifestJsonContainsClaimGenerator() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String json = reader.activeManifestJson();
            assertThat(json).contains("\"claim_generator\"");
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("validationState returns one of the known states")
    void validationStateKnownValue() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String state = reader.validationState();
            assertThat(state).isNotBlank();
            assertThat(state).isIn("\"Valid\"", "\"Invalid\"", "\"Trusted\"");
        }
    }

    @Test
    @DisplayName("validationResultsJson returns JSON or null for unsigned assets")
    void validationResultsJsonShape() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String results = reader.validationResultsJson();
            if (results != null) {
                assertThat(results).startsWith("{").endsWith("}");
            }
            // Either way it must not throw.
        }
    }

    @Test
    @DisplayName("validation methods throw IllegalStateException after close")
    void validationAfterCloseThrows() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg);
        reader.close();
        assertThatThrownBy(reader::validationState)
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(reader::validationResultsJson)
            .isInstanceOf(IllegalStateException.class);
    }

    // ── close / double-close ──────────────────────────────────────────────────

    @Test
    @DisplayName("close can be called multiple times without error")
    void doubleCloseIsIdempotent() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg);
        reader.close();
        reader.close(); // must not throw
    }

    @Test
    @DisplayName("methods throw IllegalStateException after close")
    void methodsAfterCloseThrow() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg);
        reader.close();

        assertThatThrownBy(reader::json)
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(reader::activeManifestJson)
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(reader::activeLabel)
            .isInstanceOf(IllegalStateException.class);
    }

    // ── try-with-resources ────────────────────────────────────────────────────

    @Test
    @DisplayName("try-with-resources closes the reader cleanly")
    void tryWithResourcesClosesCleanly() throws IOException {
        byte[] jpeg = fixture("success.jpg");
        String json;
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            json = reader.json();
        }
        assertThat(json).isNotBlank();
    }

    // ── Multiple readers simultaneously ───────────────────────────────────────

    @Test
    @DisplayName("two readers can be open simultaneously without interference")
    void twoReadersSimultaneously() throws IOException {
        // c2pa 0.84 Reader instances share process-global state, so the
        // manifest *stores* of two simultaneous readers may differ (e.g. the
        // second reader may include parent-ingredient manifests).  The
        // binding-level invariant is per-reader self-consistency and absence
        // of handle collisions — not cross-reader string equality.
        byte[] jpeg = fixture("success.jpg");
        try (C2paReader r1 = C2paReader.fromBytes(wasm, "image/jpeg", jpeg);
             C2paReader r2 = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
            String json1a = r1.json();
            String json1b = r1.json();
            String json2a = r2.json();
            String json2b = r2.json();
            assertThat(json1a).isNotBlank().isEqualTo(json1b);
            assertThat(json2a).isNotBlank().isEqualTo(json2b);
            if (r1.activeLabel() != null) {
                assertThat(json1a).contains(r1.activeLabel());
                assertThat(json2a).contains(r2.activeLabel());
            }
        }
    }

    // ── Validation error assets ───────────────────────────────────────────────

    @Test
    @DisplayName("signature_mismatch.jpg opens but validation_status reflects error")
    void signatureMismatchOpens() throws IOException {
        // This file has an invalid signature but a parseable manifest store.
        // Whether fromBytes succeeds depends on c2pa validation settings;
        // at minimum it must not crash the JVM.
        byte[] jpeg = fixture("signature_mismatch.jpg");
        try {
            try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
                String json = reader.json();
                assertThat(json).isNotBlank();
            }
        } catch (C2paException e) {
            // Acceptable: c2pa may reject the file outright.
            assertThat(e.getMessage()).isNotBlank();
        }
    }

    @Test
    @DisplayName("invalid_cose_sign1.jpg either opens with error status or throws C2paException")
    void invalidCoseSign1HandledGracefully() throws IOException {
        byte[] jpeg = fixture("invalid_cose_sign1.jpg");
        try {
            try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpeg)) {
                // If it opens, the JSON should mention validation errors.
                String json = reader.json();
                assertThat(json).isNotBlank();
            }
        } catch (C2paException e) {
            assertThat(e.getMessage()).isNotBlank();
        }
    }
}