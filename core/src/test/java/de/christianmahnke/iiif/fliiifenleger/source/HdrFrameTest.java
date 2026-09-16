// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HdrFrame}: validation, SDR rendition mapping and the
 * {@link BufferedImage} round trip.
 */
@DisplayName("HdrFrame")
class HdrFrameTest {

    @Test
    @DisplayName("constructor rejects bad dimensions, channels and buffers")
    void validation() {
        float[] ok = new float[2 * 2 * 3];
        assertThrows(IllegalArgumentException.class, () -> new HdrFrame(0, 2, 3, ok,
            HdrFrame.TransferFunction.SRGB, HdrFrame.Primaries.BT709));
        assertThrows(IllegalArgumentException.class, () -> new HdrFrame(2, 2, 2, new float[8],
            HdrFrame.TransferFunction.SRGB, HdrFrame.Primaries.BT709));
        assertThrows(IllegalArgumentException.class, () -> new HdrFrame(2, 2, 3, new float[17],
            HdrFrame.TransferFunction.SRGB, HdrFrame.Primaries.BT709));
        assertThrows(IllegalArgumentException.class, () -> new HdrFrame(2, 2, 3, ok, null,
            HdrFrame.Primaries.BT709));
    }

    @Test
    @DisplayName("RGB rendition swizzles to BGR byte order")
    void rgbRendition() {
        // Pure red linear pixel.
        HdrFrame frame = new HdrFrame(1, 1, 3, new float[]{1.0f, 0.0f, 0.0f},
            HdrFrame.TransferFunction.LINEAR, HdrFrame.Primaries.BT709);
        BufferedImage image = frame.toBufferedImage();
        assertEquals(BufferedImage.TYPE_3BYTE_BGR, image.getType());
        int rgb = image.getRGB(0, 0);
        assertEquals(255, (rgb >> 16) & 0xFF);
        assertEquals(0, (rgb >> 8) & 0xFF);
        assertEquals(0, rgb & 0xFF);
    }

    @Test
    @DisplayName("rendition clamps out-of-range values and NaN")
    void clampRendition() {
        HdrFrame frame = new HdrFrame(3, 1, 1,
            new float[]{8.0f, -2.0f, Float.NaN},
            HdrFrame.TransferFunction.PQ, HdrFrame.Primaries.BT2020);
        BufferedImage image = frame.toBufferedImage();
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, image.getType());
        assertEquals(255, image.getRaster().getSample(0, 0, 0));
        assertEquals(0, image.getRaster().getSample(1, 0, 0));
        assertEquals(0, image.getRaster().getSample(2, 0, 0));
    }

    @Test
    @DisplayName("SDR frame round-trips through BufferedImage")
    void sdrRoundTrip() {
        BufferedImage original = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        original.setRGB(0, 0, 0xFF123456);
        original.setRGB(3, 3, 0xFFABCDEF);
        HdrFrame frame = HdrFrame.fromBufferedImage(original);
        assertEquals(4, frame.width());
        assertEquals(4, frame.height());
        assertEquals(3, frame.channels());
        assertEquals(HdrFrame.TransferFunction.SRGB, frame.transfer());
        BufferedImage back = frame.toBufferedImage();
        assertEquals(0xFF123456, back.getRGB(0, 0));
        assertEquals(0xFFABCDEF, back.getRGB(3, 3));
    }
}
