// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InfoJsonValidator")
class InfoJsonValidatorTest {

    private static final String VALID_V2 = """
            {
              "@context": "http://iiif.io/api/image/2/context.json",
              "@id": "http://localhost:8887/iiif/page011",
              "protocol": "http://iiif.io/api/image",
              "profile": ["http://iiif.io/api/image/2/level2.json"],
              "width": 800,
              "height": 600,
              "tiles": [{"width": 512, "scaleFactors": [1, 2, 4]}],
              "sizes": [{"width": 800, "height": 600}]
            }
            """;

    private static final String VALID_V3 = """
            {
              "@context": "http://iiif.io/api/image/3/context.json",
              "id": "http://localhost:8887/iiif/page011",
              "type": "ImageService3",
              "protocol": "http://iiif.io/api/image",
              "profile": "level2",
              "width": 800,
              "height": 600,
              "tiles": [{"width": 512, "height": 512, "scaleFactors": [1, 2, 4]}],
              "sizes": [{"width": 800, "height": 600}]
            }
            """;

    @Test
    @DisplayName("valid V2 base document passes")
    void validV2Passes() {
        var result = InfoJsonValidator.validate(VALID_V2, ImageInfo.IIIFVersion.V2);
        assertTrue(result.valid(), () -> "expected valid, got: " + result.errors());
        assertEquals(ImageInfo.IIIFVersion.V2, result.detectedVersion());
    }

    @Test
    @DisplayName("valid V3 base document passes")
    void validV3Passes() {
        var result = InfoJsonValidator.validate(VALID_V3, ImageInfo.IIIFVersion.V3);
        assertTrue(result.valid(), () -> "expected valid, got: " + result.errors());
        assertEquals(ImageInfo.IIIFVersion.V3, result.detectedVersion());
    }

    @Test
    @DisplayName("auto-detection picks the right schema")
    void autoDetection() {
        assertTrue(InfoJsonValidator.validate(VALID_V2).valid());
        assertTrue(InfoJsonValidator.validate(VALID_V3).valid());
        assertEquals(ImageInfo.IIIFVersion.V2, InfoJsonValidator.validate(VALID_V2).detectedVersion());
        assertEquals(ImageInfo.IIIFVersion.V3, InfoJsonValidator.validate(VALID_V3).detectedVersion());
    }

    @Test
    @DisplayName("missing required width fails")
    void missingWidthFails() {
        String json = VALID_V3.replace("\"width\": 800,", "");
        var result = InfoJsonValidator.validate(json);
        assertFalse(result.valid());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    @DisplayName("V2 document with trustAnchor fails")
    void v2TrustAnchorFails() {
        String json = VALID_V2.replace("\"tiles\"",
                "\"service\": [{\"@id\": \"http://example.org/s\", \"profile\": \"http://example.org/p\", \"trustAnchor\": \"https://example.org/trust\"}], \"tiles\"");
        var result = InfoJsonValidator.validate(json, ImageInfo.IIIFVersion.V2);
        assertFalse(result.valid(), "V2 must reject namespaced trustAnchor");
    }

    @Test
    @DisplayName("V3 @context array with IIIF context first fails (must be last)")
    void v3ContextOrderFails() {
        String json = VALID_V3.replace(
                "\"http://iiif.io/api/image/3/context.json\"",
                "[\"http://iiif.io/api/image/3/context.json\", \"https://example.org/ext/context.json\"]");
        var result = InfoJsonValidator.validate(json);
        assertFalse(result.valid());
    }

    @Test
    @DisplayName("V3 service trustAnchor that is not a URI fails")
    void v3TrustAnchorMustBeUri() {
        String json = VALID_V3.replace("\"tiles\"",
                "\"service\": [{\"id\": \"https://example.org/s\", \"type\": \"Service\", "
                + "\"profile\": \"https://example.org/ext/\", \"trustAnchor\": \"not-a-uri\"}], \"tiles\"");
        var result = InfoJsonValidator.validate(json);
        assertFalse(result.valid());
    }

    @Test
    @DisplayName("version mismatch is reported")
    void versionMismatchReported() {
        var result = InfoJsonValidator.validate(VALID_V2, ImageInfo.IIIFVersion.V3);
        assertFalse(result.valid());
    }

    @Test
    @DisplayName("malformed JSON fails with a clear error")
    void malformedJsonFails() {
        var result = InfoJsonValidator.validate("{not json");
        assertFalse(result.valid());
        assertFalse(result.errors().isEmpty());
    }
}
