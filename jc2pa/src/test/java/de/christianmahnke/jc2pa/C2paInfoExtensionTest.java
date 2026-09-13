// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.jc2pa;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("C2paTileSink info.json extension")
class C2paInfoExtensionTest {

    @Test
    @DisplayName("V3 advertises the C2PA service without trustAnchor by default")
    void v3DefaultAdvertisesService() {
        C2paTileSink sink = new C2paTileSink();
        TileSink.InfoExtension ext = sink.getInfoJsonExtension(ImageInfo.IIIFVersion.V3);
        assertThat(ext.contexts()).containsExactly(C2paTileSink.C2PA_CONTEXT_URI);
        assertThat(ext.features()).containsExactly(C2paTileSink.C2PA_PROFILE_URI);
        assertThat(ext.services()).hasSize(1);
        assertThat(ext.services().get(0).get("profile")).isEqualTo(C2paTileSink.C2PA_PROFILE_URI);
        assertThat(ext.services().get(0)).doesNotContainKey("trustAnchor");
    }

    @Test
    @DisplayName("V3 writes trust-anchor into the service entry")
    void v3TrustAnchorInService() {
        C2paTileSink sink = new C2paTileSink();
        sink.setOptions(Map.of("trust-anchor", "https://example.org/trust/anchor"));
        TileSink.InfoExtension ext = sink.getInfoJsonExtension(ImageInfo.IIIFVersion.V3);
        assertThat(ext.services().get(0).get("trustAnchor")).isEqualTo("https://example.org/trust/anchor");
    }

    @Test
    @DisplayName("V2 advertises the C2PA URI via supports when no trust-anchor is set")
    void v2WithoutAnchorAdvertisesSupports() {
        C2paTileSink sink = new C2paTileSink();
        TileSink.InfoExtension ext = sink.getInfoJsonExtension(ImageInfo.IIIFVersion.V2);
        assertThat(ext.contexts()).isEmpty();
        assertThat(ext.features()).containsExactly(C2paTileSink.C2PA_PROFILE_URI);
    }

    @Test
    @DisplayName("V2 with trust-anchor fails (no namespaced options in Image API 2)")
    void v2WithAnchorFails() {
        C2paTileSink sink = new C2paTileSink();
        sink.setOptions(Map.of("trust-anchor", "https://example.org/trust/anchor"));
        assertThatThrownBy(() -> sink.getInfoJsonExtension(ImageInfo.IIIFVersion.V2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Image API 3");
    }

    @Test
    @DisplayName("trust-anchor must be an absolute URI")
    void trustAnchorMustBeAbsoluteUri() {
        C2paTileSink sink = new C2paTileSink();
        assertThatThrownBy(() -> sink.setOptions(Map.of("trust-anchor", "not-a-uri")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust-anchor");
        assertThatThrownBy(() -> sink.setOptions(Map.of("trust-anchor", "relative/path")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
