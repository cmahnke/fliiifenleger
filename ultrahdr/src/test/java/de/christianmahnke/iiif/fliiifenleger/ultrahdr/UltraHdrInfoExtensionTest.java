// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UltraHdrTileSink info.json extension")
class UltraHdrInfoExtensionTest {

    @Test
    @DisplayName("V3 advertises the HDR service")
    void v3AdvertisesHdrService() {
        UltraHdrTileSink sink = new UltraHdrTileSink();
        TileSink.InfoExtension ext = sink.getInfoJsonExtension(ImageInfo.IIIFVersion.V3);
        assertThat(ext.contexts()).containsExactly(TileSink.HDR_CONTEXT_URI);
        assertThat(ext.features()).containsExactly(TileSink.HDR_PROFILE_URI);
        assertThat(ext.services()).hasSize(1);
        assertThat(ext.services().get(0).get("profile")).isEqualTo(TileSink.HDR_PROFILE_URI);
    }

    @Test
    @DisplayName("V2 advertises the HDR URI via supports")
    void v2AdvertisesHdrSupports() {
        UltraHdrTileSink sink = new UltraHdrTileSink();
        TileSink.InfoExtension ext = sink.getInfoJsonExtension(ImageInfo.IIIFVersion.V2);
        assertThat(ext.contexts()).isEmpty();
        assertThat(ext.services()).isEmpty();
        assertThat(ext.features()).containsExactly(TileSink.HDR_PROFILE_URI);
    }
}
