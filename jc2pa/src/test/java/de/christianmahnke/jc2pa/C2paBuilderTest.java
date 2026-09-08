// src/test/java/de/christianmahnke/jc2pa/C2paBuilderTest.java
package de.christianmahnke.jc2pa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link C2paBuilder}.
 *
 * <p>Signing happens inside the WASM module, so real round-trips
 * (build → sign → read → verify) can be exercised without any external
 * signing infrastructure by using the ephemeral signer.  Tests using
 * {@link C2paBuilder#signWithKeys} without valid key material verify the
 * error paths.
 */
@DisplayName("C2paBuilder")
class C2paBuilderTest extends AbstractWasmTest {

    // ── Minimal valid manifest JSON used across tests ─────────────────────────

    private static final String MINIMAL_MANIFEST_JSON = """
        {
          "claim_generator": "jc2pa-test/0.1",
          "title": "Test Asset",
          "assertions": []
        }
        """;

    private static final String MANIFEST_WITH_ASSERTION = """
        {
          "claim_generator": "jc2pa-test/0.1",
          "title": "Test Asset with Assertion",
          "assertions": [
            {
              "label": "c2pa.training-mining",
              "data": {
                "entries": {
                  "c2pa.ai_generative_training": { "use": "notAllowed" }
                }
              }
            }
          ]
        }
        """;

    /** JPEG SOI marker: FF D8 FF (bytes cast explicitly to avoid javac errors). */
    private static final byte[] JPEG_SOI = {
        (byte) 0xFF, (byte) 0xD8, (byte) 0xFF
    };

    // ── Construction ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("builder_from_json succeeds for minimal valid JSON")
    void fromJsonMinimalValid() throws Exception {
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            assertThat(builder).isNotNull();
        }
    }

    @Test
    @DisplayName("builder_from_json succeeds for manifest with assertion")
    void fromJsonWithAssertion() throws Exception {
        try (C2paBuilder builder = new C2paBuilder(wasm, MANIFEST_WITH_ASSERTION)) {
            assertThat(builder).isNotNull();
        }
    }

    @Test
    @DisplayName("builder_from_json throws C2paException for empty string")
    void fromJsonEmptyStringThrows() {
        assertThatThrownBy(() ->
            new C2paBuilder(wasm, "").close())
            .isInstanceOf(C2paException.class);
    }

    @Test
    @DisplayName("builder_from_json throws C2paException for invalid JSON")
    void fromJsonInvalidJsonThrows() {
        assertThatThrownBy(() ->
            new C2paBuilder(wasm, "not valid json {{{{").close())
            .isInstanceOf(C2paException.class);
    }

    @Test
    @DisplayName("builder_from_json with valid JSON but wrong schema does not crash JVM")
    void fromJsonWrongSchemaDoesNotCrash() {
        // Valid JSON but not a manifest — missing required fields.
        // c2pa may reject this at build time or at sign time; either is acceptable.
        try {
            try (C2paBuilder builder = new C2paBuilder(wasm, "{\"foo\":\"bar\"}")) {
                assertThat(builder).isNotNull();
            }
        } catch (C2paException e) {
            // Acceptable — WASM rejected the schema at construction time.
            assertThat(e.getMessage()).isNotBlank();
        }
    }

    // ── close / lifecycle ─────────────────────────────────────────────────────

    @Test
    @DisplayName("close can be called multiple times without error")
    void doubleCloseIsIdempotent() throws Exception {
        C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON);
        builder.close();
        builder.close(); // must not throw
    }

    @Test
    @DisplayName("signEphemeral throws IllegalStateException after close")
    void signEphemeralAfterCloseThrows() throws Exception {
        C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON);
        builder.close();
        assertThatThrownBy(() ->
            builder.signEphemeral("image/jpeg", JPEG_SOI, "test"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("signWithKeys throws IllegalStateException after close")
    void signWithKeysAfterCloseThrows() throws Exception {
        C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON);
        builder.close();
        assertThatThrownBy(() ->
            builder.signWithKeys("image/jpeg", JPEG_SOI,
                new byte[0], new byte[0], "es256", null))
            .isInstanceOf(IllegalStateException.class);
    }

    // ── signEphemeral round-trip ──────────────────────────────────────────────

    @Test
    @DisplayName("signEphemeral signs a valid JPEG and the result opens with a manifest")
    void signEphemeralRoundTrip() throws Exception {
        byte[] jpeg = fixture("success.jpg");

        byte[] signed;
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            signed = builder.signEphemeral("image/jpeg", jpeg, "jc2pa-test");
        }

        // Note: c2pa may strip a pre-existing (legacy) manifest store when
        // embedding the new one, so the signed output can be smaller than
        // the input asset.
        assertThat(signed).isNotEmpty().hasSizeGreaterThan(1000);

        // The signed asset must open and expose the active manifest.
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", signed)) {
            assertThat(reader.activeLabel()).isNotBlank();
            assertThat(reader.json()).isNotBlank();
        }
    }

    @Test
    @DisplayName("signEphemeral round-trip preserves the manifest title")
    void signEphemeralRoundTripPreservesTitle() throws Exception {
        byte[] jpeg = fixture("success.jpg");

        byte[] signed;
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            signed = builder.signEphemeral("image/jpeg", jpeg, "jc2pa-test");
        }

        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", signed)) {
            assertThat(reader.activeManifestJson()).contains("Test Asset");
        }
    }

    @Test
    @DisplayName("signEphemeral throws C2paException for a non-JPEG asset")
    void signEphemeralInvalidAssetThrows() throws Exception {
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            assertThatThrownBy(() ->
                builder.signEphemeral("image/jpeg", new byte[]{0x01}, "jc2pa-test"))
                .isInstanceOf(C2paException.class);
        }
    }

    @Test
    @DisplayName("signEphemeral throws C2paException for empty asset bytes")
    void signEphemeralEmptyAssetThrows() throws Exception {
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            assertThatThrownBy(() ->
                builder.signEphemeral("image/jpeg", new byte[0], "jc2pa-test"))
                .isInstanceOf(C2paException.class);
        }
    }

    @Test
    @DisplayName("signEphemeral cannot be called twice on the same builder")
    void signEphemeralConsumesBuilder() throws Exception {
        byte[] jpeg = fixture("success.jpg");
        C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON);
        builder.signEphemeral("image/jpeg", jpeg, "jc2pa-test");
        // The first sign consumed the builder — the second must be rejected.
        assertThatThrownBy(() ->
            builder.signEphemeral("image/jpeg", jpeg, "jc2pa-test"))
            .isInstanceOf(IllegalStateException.class);
    }

    // ── signWithKeys error paths ──────────────────────────────────────────────

    @Test
    @DisplayName("signWithKeys throws C2paException for invalid PEM key material")
    void signWithKeysInvalidPemThrows() throws Exception {
        byte[] jpeg = fixture("success.jpg");
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            assertThatThrownBy(() ->
                builder.signWithKeys(
                    "image/jpeg",
                    jpeg,
                    "not a certificate".getBytes(),
                    "not a key".getBytes(),
                    "es256",
                    null))
                .isInstanceOf(C2paException.class);
        }
    }

    @Test
    @DisplayName("signWithKeys throws C2paException for unknown algorithm")
    void signWithKeysUnknownAlgThrows() throws Exception {
        byte[] jpeg = fixture("success.jpg");
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            assertThatThrownBy(() ->
                builder.signWithKeys(
                    "image/jpeg",
                    jpeg,
                    "not a certificate".getBytes(),
                    "not a key".getBytes(),
                    "not_a_real_alg",
                    null))
                .isInstanceOf(C2paException.class);
        }
    }

    @Test
    @DisplayName("signWithKeys with null key material does not throw NullPointerException")
    void signWithKeysNullKeyMaterial() throws Exception {
        byte[] jpeg = fixture("success.jpg");
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            assertThatThrownBy(() ->
                builder.signWithKeys(
                    "image/jpeg",
                    jpeg,
                    null,   // null → treated as empty byte[] by the wrapper
                    null,
                    "es256",
                    null))
                .isInstanceOf(C2paException.class)
                .hasMessageNotContaining("NullPointerException");
        }
    }

    // ── Intent ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setIntent accepts edit, update, and create with a source type")
    void setIntentAcceptsValidIntents() throws Exception {
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            builder.setIntent("edit");
            builder.setIntent("update");
            builder.setIntent("create:digitalCapture");
        }
    }

    @Test
    @DisplayName("setIntent throws C2paException for unknown intents")
    void setIntentUnknownThrows() throws Exception {
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            assertThatThrownBy(() -> builder.setIntent("destroy"))
                .isInstanceOf(C2paException.class)
                .hasMessageContaining("unknown intent");
        }
    }

    @Test
    @DisplayName("setIntent accepts unknown digital source types (c2pa Other fallback)")
    void setIntentCustomSourceTypeAccepted() throws Exception {
        // c2pa's DigitalSourceType has an Other(String) fallback: unknown
        // source types are legal custom values, not errors.
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            builder.setIntent("create:notAThing");
        }
    }

    @Test
    @DisplayName("setIntent throws IllegalStateException after close")
    void setIntentAfterCloseThrows() throws Exception {
        C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON);
        builder.close();
        assertThatThrownBy(() -> builder.setIntent("edit"))
            .isInstanceOf(IllegalStateException.class);
    }

    // ── Ingredients ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("addIngredient accepts a valid JPEG ingredient")
    void addIngredientAcceptsJpeg() throws Exception {
        byte[] jpeg = fixture("success.jpg");
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            builder.addIngredient(
                "{\"title\": \"parent\", \"relationship\": \"parentOf\"}",
                "image/jpeg", jpeg);
        }
    }

    @Test
    @DisplayName("ingredient data is hashed as-is (arbitrary bytes are legal)")
    void addIngredientArbitraryDataIsHashed() throws Exception {
        // Ingredient content is only hashed at sign time — arbitrary bytes
        // are valid content (hashing works on any input).
        byte[] signed;
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            builder.addIngredient(
                "{\"title\": \"custom-data\", \"relationship\": \"componentOf\"}",
                "image/jpeg", new byte[]{0x01, 0x02, 0x03, 0x04});
            signed = builder.signEphemeral("image/jpeg",
                                           fixture("success.jpg"), "ingredient-test");
        }
        assertThat(signed).isNotEmpty();
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", signed)) {
            assertThat(reader.activeManifestJson()).contains("custom-data");
        }
    }

    @Test
    @DisplayName("addIngredient throws C2paException for invalid ingredient JSON")
    void addIngredientInvalidJsonThrows() throws Exception {
        byte[] jpeg = fixture("success.jpg");
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            assertThatThrownBy(() ->
                builder.addIngredient("not json", "image/jpeg", jpeg))
                .isInstanceOf(C2paException.class);
        }
    }

    @Test
    @DisplayName("addIngredient throws IllegalStateException after close")
    void addIngredientAfterCloseThrows() throws Exception {
        byte[] jpeg = fixture("success.jpg");
        C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON);
        builder.close();
        assertThatThrownBy(() ->
            builder.addIngredient("{\"title\": \"x\"}", "image/jpeg", jpeg))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("intent + ingredient round-trip: signed manifest contains both")
    void intentIngredientSignRoundTrip() throws Exception {
        byte[] jpeg = fixture("success.jpg");
        byte[] signed;
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            builder.setIntent("edit");
            builder.addIngredient(
                "{\"title\": \"parent\", \"relationship\": \"parentOf\"}",
                "image/jpeg", jpeg);
            signed = builder.signEphemeral("image/jpeg", jpeg, "lifecycle-test");
        }
        assertThat(signed).isNotEmpty();

        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", signed)) {
            assertThat(reader.activeLabel()).isNotBlank();
            // The parent ingredient must be referenced in the manifest.
            assertThat(reader.activeManifestJson()).contains("parentOf");
        }
    }

    // ── Multiple builders simultaneously ──────────────────────────────────────

    @Test
    @DisplayName("two builders can be open simultaneously without handle collision")
    void twoBuildersSimultaneously() throws Exception {
        try (C2paBuilder b1 = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON);
             C2paBuilder b2 = new C2paBuilder(wasm, MANIFEST_WITH_ASSERTION)) {
            // Both handles are valid — verified by successful construction.
            assertThat(b1).isNotNull();
            assertThat(b2).isNotNull();
        }
    }

    @Test
    @DisplayName("ten builders can be open simultaneously without crash")
    void tenBuildersSimultaneously() throws Exception {
        C2paBuilder[] builders = new C2paBuilder[10];
        try {
            for (int i = 0; i < builders.length; i++) {
                builders[i] = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON);
                assertThat(builders[i]).isNotNull();
            }
        } finally {
            // Always close all builders even if one construction failed.
            for (C2paBuilder b : builders) {
                if (b != null) {
                    b.close();
                }
            }
        }
    }
}
