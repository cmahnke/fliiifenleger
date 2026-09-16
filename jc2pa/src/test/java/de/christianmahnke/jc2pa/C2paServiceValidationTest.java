// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.jc2pa;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.InfoJsonValidator;
import de.christianmahnke.iiif.fliiifenleger.validation.ValidationResult;
import de.christianmahnke.iiif.fliiifenleger.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("C2PA service info.json validation")
class C2paServiceValidationTest {

    private static String serviceDoc(String context, String service) {
        return "{"
                + "\"@context\": " + context + ","
                + "\"id\": \"http://localhost:8887/iiif/page011\","
                + "\"type\": \"ImageService3\","
                + "\"protocol\": \"http://iiif.io/api/image\","
                + "\"profile\": \"level2\","
                + "\"width\": 800,"
                + "\"height\": 600,"
                + "\"tiles\": [{\"width\": 512, \"scaleFactors\": [1, 2]}],"
                + "\"extraFeatures\": [\"" + C2paTileSink.C2PA_PROFILE_URI + "\"],"
                + "\"service\": [" + service + "]"
                + "}";
    }

    private static String c2paService(String extra) {
        return "{\"id\": \"" + C2paTileSink.C2PA_PROFILE_URI + "\", \"type\": \"Service\", "
                + "\"profile\": \"" + C2paTileSink.C2PA_PROFILE_URI + "\"" + extra + "}";
    }

    private static String contexts() {
        return "[\"" + C2paTileSink.C2PA_CONTEXT_URI + "\", \"http://iiif.io/api/image/3/context.json\"]";
    }

    @Test
    @DisplayName("V3 C2PA service with trustAnchor passes")
    void c2paServiceWithAnchorPasses() {
        String json = serviceDoc(contexts(), c2paService(", \"trustAnchor\": \"https://example.org/trust/anchor\""));
        ValidationResult result =
                InfoJsonValidator.validate(json, ImageInfo.IIIFVersion.V3);
        assertThat(result.valid()).as(() -> "expected valid, got: " + result.errors()).isTrue();
    }

    @Test
    @DisplayName("V3 C2PA service with a non-URI trustAnchor fails")
    void c2paServiceWithBadAnchorFails() {
        String json = serviceDoc(contexts(), c2paService(", \"trustAnchor\": \"not-a-uri\""));
        ValidationResult result =
                InfoJsonValidator.validate(json, ImageInfo.IIIFVersion.V3);
        assertThat(result.valid()).isFalse();
    }

    @Test
    @DisplayName("c2pa validator is discovered via ServiceLoader")
    void c2paValidatorDiscovered() {
        assertThat(Validator.loadAll().stream().map(Validator::getName)).contains("c2pa");
        assertThat(InfoJsonValidator.VALIDATOR_REGISTRY).containsKey("c2pa");
    }

    @Test
    @DisplayName("V3 C2PA service without its context fails")
    void c2paServiceWithoutContextFails() {
        String json = serviceDoc("\"http://iiif.io/api/image/3/context.json\"", c2paService(""));
        ValidationResult result = InfoJsonValidator.validate(json);
        assertThat(result.valid()).isFalse();
    }

    @Test
    @DisplayName("V3 C2PA service missing its id fails")
    void c2paServiceMissingIdFails() {
        String service = "{\"type\": \"Service\", \"profile\": \"" + C2paTileSink.C2PA_PROFILE_URI + "\"}";
        String json = serviceDoc(contexts(), service);
        ValidationResult result = InfoJsonValidator.validate(json);
        assertThat(result.valid()).isFalse();
    }
}
