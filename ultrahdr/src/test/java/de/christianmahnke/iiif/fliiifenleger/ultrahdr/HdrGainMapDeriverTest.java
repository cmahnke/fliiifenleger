// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import de.christianmahnke.iiif.fliiifenleger.source.HdrFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("HdrGainMapDeriver")
class HdrGainMapDeriverTest {

    private static BufferedImage solidTile(int w, int h, Color color) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static HdrFrame linearFrame(int w, int h, float value) {
        float[] pixels = new float[w * h * 3];
        Arrays.fill(pixels, value);
        return new HdrFrame(w, h, 3, pixels,
            HdrFrame.TransferFunction.LINEAR, HdrFrame.Primaries.BT709);
    }

    @Test
    @DisplayName("uniform boost yields a uniform gain plane matching the ratio")
    void uniformBoostYieldsUniformPlane() {
        // LINEAR 2.0 against a white SDR base: boost (2+o)/(1+o) with
        // offsets o = 1/64, normalized by ln(ceiling 6.0).
        HdrFrame frame = linearFrame(8, 8, 2.0f);
        BufferedImage sdr = solidTile(8, 8, Color.WHITE);
        HdrGainMapDeriver.Derivation derivation =
            HdrGainMapDeriver.derive(frame, 0, 0, 8, 8, sdr);
        HdrFrame.GainMap gainMap = derivation.gainMap();
        // Quarter resolution (scale factor 4).
        assertThat(gainMap.width()).isEqualTo(2);
        assertThat(gainMap.height()).isEqualTo(2);
        double boost = (2.0 + 1.0 / 64.0) / (1.0 + 1.0 / 64.0);
        double expectedV = Math.log(boost) / Math.log(6.0);
        for (float v : gainMap.pixels()) {
            assertThat((double) v).isCloseTo(expectedV, within(1e-6));
        }
        String expectedMax = String.format(java.util.Locale.ROOT, "%.6f",
            Math.log(boost) / Math.log(2));
        assertThat(derivation.metadataJson())
            .contains("\"gainMapMax\": [" + expectedMax + ", " + expectedMax + ", " + expectedMax + "]");
    }

    @Test
    @DisplayName("black on black yields a zero gain plane")
    void blackOnBlackYieldsZeroPlane() {
        HdrFrame frame = linearFrame(8, 8, 0.0f);
        BufferedImage sdr = solidTile(8, 8, Color.BLACK);
        HdrGainMapDeriver.Derivation derivation =
            HdrGainMapDeriver.derive(frame, 0, 0, 8, 8, sdr);
        for (float v : derivation.gainMap().pixels()) {
            assertThat(v).isZero();
        }
        assertThat(derivation.metadataJson()).contains("\"gainMapMax\": [0.000000, 0.000000, 0.000000]");
    }

    @Test
    @DisplayName("metadata JSON carries the full ISO schema")
    void metadataCarriesFullSchema() {
        HdrFrame frame = linearFrame(8, 8, 2.0f);
        BufferedImage sdr = solidTile(8, 8, Color.WHITE);
        String json = HdrGainMapDeriver.derive(frame, 0, 0, 8, 8, sdr).metadataJson();
        assertThat(json).contains("\"gainMapMin\"");
        assertThat(json).contains("\"gamma\"");
        assertThat(json).contains("\"baseOffset\"");
        assertThat(json).contains("\"alternateOffset\"");
        assertThat(json).contains("\"baseHdrHeadroom\": 0.0");
        // Headroom ceiling log2(6.0): the uniform ~2x tile peaks below it.
        assertThat(json).contains("\"alternateHdrHeadroom\": 2.584963");
        assertThat(json).contains("\"useBaseColorSpace\": true");
        assertThat(json).contains("\"backwardDirection\": false");
    }

    @Test
    @DisplayName("PQ peak decodes above diffuse white")
    void pqPeakDecodesAboveDiffuse() {
        // PQ 1.0 is 10 000 nits = 100x the 100-nit diffuse anchor.
        assertThat(HdrGainMapDeriver.pqEotf(1.0)).isCloseTo(1.0, within(1e-6));
        assertThat(HdrGainMapDeriver.diffuseAnchor(HdrFrame.TransferFunction.PQ))
            .isCloseTo(0.01, within(1e-12));
        // HLG reference white decodes to scene signal ~0.265 (BT.2100).
        assertThat(HdrGainMapDeriver.hlgOetfInverse(0.75)).isCloseTo(0.264963, within(1e-6));
    }

    @Test
    @DisplayName("UNKNOWN transfer refuses derivation")
    void unknownTransferRefuses() {
        float[] pixels = new float[4 * 4 * 3];
        Arrays.fill(pixels, 0.5f);
        HdrFrame frame = new HdrFrame(4, 4, 3, pixels,
            HdrFrame.TransferFunction.UNKNOWN, HdrFrame.Primaries.UNKNOWN);
        assertThatThrownBy(() ->
            HdrGainMapDeriver.derive(frame, 0, 0, 4, 4, solidTile(4, 4, Color.GRAY)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("out-of-bounds region is rejected")
    void outOfBoundsRegionRejected() {
        HdrFrame frame = linearFrame(8, 8, 1.0f);
        BufferedImage sdr = solidTile(8, 8, Color.WHITE);
        assertThatThrownBy(() ->
            HdrGainMapDeriver.derive(frame, 4, 4, 8, 8, sdr))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
