// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/test/java/de/christianmahnke/jc2pa/C2paSignWithKeysRoundTripTest.java
package de.christianmahnke.jc2pa;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Security;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for the key-based signing path.
 *
 * <p>Generates an EC P-256 CA and end-entity certificate at test time (the
 * chain shape c2pa requires — c2pa rejects bare self-signed end-entity
 * certificates without issuer/AKI structure), signs a JPEG via
 * {@link C2paBuilder#signWithKeys}, and validates the result through
 * {@link C2paReader}.
 *
 * <p>The generated chain is not on any C2PA trust list, so validation is
 * asserted structurally (manifest present, title preserved, validation state
 * queryable) rather than as {@code Valid}/{@code Trusted}.
 */
@DisplayName("C2paSignWithKeysRoundTrip")
class C2paSignWithKeysRoundTripTest extends AbstractWasmTest {

    private static final String MINIMAL_MANIFEST_JSON = """
        {
          "claim_generator": "jc2pa-test/0.1",
          "title": "Test Asset",
          "assertions": []
        }
        """;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    @DisplayName("generated ES256 cert signs a JPEG that reads back with its manifest")
    void signWithGeneratedCertRoundTrip() throws Exception {
        byte[] jpeg = fixture("success.jpg");

        byte[][] pems = generateEs256Cert("jc2pa-test");
        byte[] certPem = pems[0];
        byte[] keyPem = pems[1];
        String chainPem = new String(certPem, StandardCharsets.UTF_8);
        // End-entity first, then CA.
        assertThat(chainPem.split("-----BEGIN CERTIFICATE-----", -1)).hasSize(3);
        assertThat(new String(keyPem, StandardCharsets.UTF_8))
            .contains("-----BEGIN PRIVATE KEY-----");

        byte[] signed;
        try (C2paBuilder builder = new C2paBuilder(wasm, MINIMAL_MANIFEST_JSON)) {
            signed = builder.signWithKeys(
                "image/jpeg", jpeg, certPem, keyPem, "es256", null);
        }
        assertThat(signed).isNotEmpty().hasSizeGreaterThan(1000);

        try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", signed)) {
            String label = reader.activeLabel();
            assertThat(label).isNotBlank();
            assertThat(reader.json()).contains(label).contains("Test Asset");
            assertThat(reader.activeManifestJson()).contains("Test Asset");
            // Queryable but not strictly Valid: self-signed, no trust list.
            assertThat(reader.validationState())
                .isIn("\"Valid\"", "\"Invalid\"", "\"Trusted\"");
            String results = reader.validationResultsJson();
            if (results != null) {
                assertThat(results).startsWith("{").endsWith("}");
            }
        }
    }

    /**
     * Generate an EC P-256 CA plus end-entity certificate chain.
     *
     * <p>Delegates to {@link TestCertChains} (same profile, plus the CA
     * certificate alone for trust-anchor tests).
     *
     * @param commonName CN for the end-entity certificate.
     * @return {@code [chainPem, keyPem]} with the PEM-encoded chain
     *         (end-entity first, then CA) and the PKCS#8 end-entity private
     *         key in PEM encoding.
     */
    private static byte[][] generateEs256Cert(String commonName) throws Exception {
        byte[][] chain = TestCertChains.generateEs256Chain(commonName);
        return new byte[][]{chain[0], chain[1]};
    }
}
