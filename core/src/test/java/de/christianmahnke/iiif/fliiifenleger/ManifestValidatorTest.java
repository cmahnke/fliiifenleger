// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import de.christianmahnke.iiif.fliiifenleger.validation.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ManifestValidator")
class ManifestValidatorTest {

    private static String manifest(ImageInfo.IIIFVersion version) throws Exception {
        IiifManifest manifest = new IiifManifest(version, "http://example.org/iiif/book", "Book");
        manifest.addCanvas("p1", "Page 1", "http://example.org/iiif/book/image", 800, 600);
        return IiifManifest.toPrettyJson(manifest.toJson());
    }

    @Test
    @DisplayName("generated V2 manifest passes semantic checks")
    void generatedV2Passes() throws Exception {
        ValidationResult result = ManifestValidator.validate(manifest(ImageInfo.IIIFVersion.V2));
        assertTrue(result.valid(), () -> String.join("\n", result.errors()));
        assertEquals(ImageInfo.IIIFVersion.V2, result.detectedVersion());
    }

    @Test
    @DisplayName("generated V3 manifest passes official schema")
    void generatedV3Passes() throws Exception {
        ValidationResult result = ManifestValidator.validate(manifest(ImageInfo.IIIFVersion.V3));
        assertTrue(result.valid(), () -> String.join("\n", result.errors()));
        assertEquals(ImageInfo.IIIFVersion.V3, result.detectedVersion());
    }

    @Test
    @DisplayName("generated V4 manifest passes official schema")
    void generatedV4Passes() throws Exception {
        ValidationResult result = ManifestValidator.validate(manifest(ImageInfo.IIIFVersion.V4));
        assertTrue(result.valid(), () -> String.join("\n", result.errors()));
        assertEquals(ImageInfo.IIIFVersion.V4, result.detectedVersion());
    }

    @Test
    @DisplayName("missing label fails V3 schema validation")
    void missingLabelFails() throws Exception {
        String json = manifest(ImageInfo.IIIFVersion.V3).replaceFirst("\"label\"\\s*:\\s*\\{[^}]*\\},", "");
        ValidationResult result = ManifestValidator.validate(json);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("label")));
    }

    @Test
    @DisplayName("duplicate ids fail V4 validation")
    void duplicateIdsFail() throws Exception {
        IiifManifest manifest = new IiifManifest(ImageInfo.IIIFVersion.V4, "http://example.org/iiif/book", "Book");
        manifest.addCanvas("p1", "Page 1", "http://example.org/iiif/book/image1", 800, 600);
        manifest.addCanvas("p1", "Page 2", "http://example.org/iiif/book/image2", 800, 600);
        ValidationResult result = ManifestValidator.validate(IiifManifest.toPrettyJson(manifest.toJson()));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("duplicate id")));
    }

    @Test
    @DisplayName("V4 context must be last")
    void contextOrderFails() throws Exception {
        String json = manifest(ImageInfo.IIIFVersion.V4)
                .replace("\"@context\" : \"" + IiifManifest.V4_CONTEXT + "\"",
                        "\"@context\" : [\"" + IiifManifest.V4_CONTEXT + "\", \"http://example.org/context\"]");
        ValidationResult result = ManifestValidator.validate(json);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("last entry")));
    }

    @Test
    @DisplayName("version mismatch is reported")
    void versionMismatch() throws Exception {
        ValidationResult result = ManifestValidator.validate(manifest(ImageInfo.IIIFVersion.V3), ImageInfo.IIIFVersion.V4);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("Version mismatch")));
    }

    @Test
    @DisplayName("malformed JSON is rejected")
    void malformedJson() {
        ValidationResult result = ManifestValidator.validate("{not json");
        assertFalse(result.valid());
        assertTrue(result.errors().get(0).contains("not valid JSON"));
    }

    @Test
    @DisplayName("V2 missing @type is rejected")
    void v2MissingType() throws Exception {
        String json = manifest(ImageInfo.IIIFVersion.V2).replace("\"@type\" : \"sc:Manifest\",", "");
        ValidationResult result = ManifestValidator.validate(json);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("@type")));
    }
}
