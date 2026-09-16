// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.InfoJsonValidator;
import de.christianmahnke.iiif.fliiifenleger.validation.ValidationResult;
import de.christianmahnke.iiif.fliiifenleger.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HDR service info.json validation")
class HdrServiceValidationTest {

    private static String serviceDoc(String context) {
        return "{"
                + "\"@context\": " + context + ","
                + "\"id\": \"http://localhost:8887/iiif/page011\","
                + "\"type\": \"ImageService3\","
                + "\"protocol\": \"http://iiif.io/api/image\","
                + "\"profile\": \"level2\","
                + "\"width\": 800,"
                + "\"height\": 600,"
                + "\"tiles\": [{\"width\": 512, \"scaleFactors\": [1]}],"
                + "\"extraFeatures\": [\"" + UltraHdrTileSink.HDR_PROFILE_URI + "\"],"
                + "\"service\": [{\"id\": \"" + UltraHdrTileSink.HDR_PROFILE_URI + "\", \"type\": \"Service\", "
                + "\"profile\": \"" + UltraHdrTileSink.HDR_PROFILE_URI + "\"}]"
                + "}";
    }

    private static String contexts() {
        return "[\"" + UltraHdrTileSink.HDR_CONTEXT_URI + "\", \"http://iiif.io/api/image/3/context.json\"]";
    }

    @Test
    @DisplayName("V3 HDR service passes")
    void hdrServicePasses() {
        ValidationResult result =
                InfoJsonValidator.validate(serviceDoc(contexts()), ImageInfo.IIIFVersion.V3);
        assertThat(result.valid()).as(() -> "expected valid, got: " + result.errors()).isTrue();
    }

    @Test
    @DisplayName("V3 HDR service without its context fails")
    void hdrServiceWithoutContextFails() {
        String json = serviceDoc("\"http://iiif.io/api/image/3/context.json\"");
        ValidationResult result = InfoJsonValidator.validate(json);
        assertThat(result.valid()).isFalse();
    }

    @Test
    @DisplayName("hdr validator is discovered via ServiceLoader")
    void hdrValidatorDiscovered() {
        assertThat(Validator.loadAll().stream().map(Validator::getName)).contains("hdr");
        assertThat(InfoJsonValidator.VALIDATOR_REGISTRY).containsKey("hdr");
    }
}
