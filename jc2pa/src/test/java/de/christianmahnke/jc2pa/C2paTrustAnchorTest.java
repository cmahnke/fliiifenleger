// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.jc2pa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trust anchor validation: manifests signed with a known chain report
 * {@code Trusted} once its CA is installed via
 * {@link C2paWasm#trustAnchorsSet}, and fall back otherwise.
 *
 * <p>Trust anchors are process-global in the WASM module, so every test
 * clears them afterwards (shared instance, see {@link AbstractWasmTest}).
 */
@DisplayName("C2paTrustAnchor")
class C2paTrustAnchorTest extends AbstractWasmTest {

    // Anchored validation is strict: the manifest needs a created/opened
    // action, otherwise the store reports Invalid (unanchored validation
    // is lenient about this and stays Valid).  The digitalSourceType
    // value mirrors c2pa-rs' own test fixtures.
    private static final String MINIMAL_MANIFEST_JSON = """
        {
          "claim_generator": "jc2pa-test/0.1",
          "title": "Test Asset",
          "assertions": [
            {
              "label": "c2pa.actions",
              "data": {"actions": [{"action": "c2pa.created",
                                    "digitalSourceType": "http://c2pa.org/digitalsourcetype/empty"}]}
            }
          ]
        }
        """;

    @AfterEach
    void clearAnchors() {
        wasm.trustAnchorsClear();
    }

    private static byte[] signedTile(byte[] chainPem, byte[] keyPem) throws Exception {
        byte[] jpeg = fixture("success.jpg");
        try (TileSigner signer = new TileSigner((String) null)) {
            return signer.sign(jpeg, "image/jpeg", MINIMAL_MANIFEST_JSON,
                               chainPem, keyPem, "es256", null);
        }
    }

    private static String validationState(byte[] asset) throws Exception {
        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", asset)) {
            return reader.validationState();
        }
    }

    @Test
    @DisplayName("anchored chain validates as Trusted")
    void anchoredChainIsTrusted() throws Exception {
        byte[][] chain = TestCertChains.generateEs256Chain("jc2pa-trust-test");
        byte[] signed = signedTile(chain[0], chain[1]);

        // Unanchored: structurally valid, but not trusted.
        assertThat(validationState(signed)).doesNotContain("Trusted");

        wasm.trustAnchorsSet(new String(chain[2], StandardCharsets.UTF_8));
        try {
            assertThat(validationState(signed)).contains("Trusted");
        } finally {
            wasm.trustAnchorsClear();
        }
        assertThat(validationState(signed)).doesNotContain("Trusted");
    }

    @Test
    @DisplayName("foreign anchor does not confer trust")
    void foreignAnchorDoesNotTrust() throws Exception {
        byte[][] chain = TestCertChains.generateEs256Chain("jc2pa-trust-test");
        byte[][] other = TestCertChains.generateEs256Chain("jc2pa-trust-other");
        byte[] signed = signedTile(chain[0], chain[1]);

        wasm.trustAnchorsSet(new String(other[2], StandardCharsets.UTF_8));
        try {
            assertThat(validationState(signed)).doesNotContain("Trusted");
        } finally {
            wasm.trustAnchorsClear();
        }
    }

    @Test
    @DisplayName("malformed anchor bundle is rejected")
    void malformedAnchorRejected() {
        assertThatThrownBy(() -> wasm.trustAnchorsSet("not a PEM bundle"))
            .isInstanceOf(C2paException.class);
    }
}
