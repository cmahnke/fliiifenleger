// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HdrFrame.GainMap")
class HdrFrameGainMapTest {

    private static HdrFrame.GainMap gainMap(int w, int h, int channels) {
        return new HdrFrame.GainMap(w, h, channels, new float[w * h * channels], "{}");
    }

    @Test
    @DisplayName("frames default to no gain map, opt in explicitly")
    void frameGainMapIsNullable() {
        float[] pixels = new float[2 * 2 * 3];
        HdrFrame plain = new HdrFrame(2, 2, 3, pixels,
            HdrFrame.TransferFunction.SRGB, HdrFrame.Primaries.BT709);
        assertNull(plain.gainmap());
        assertFalse(plain.hasGainMap());

        HdrFrame withGain = new HdrFrame(2, 2, 3, pixels,
            HdrFrame.TransferFunction.SRGB, HdrFrame.Primaries.BT709,
            gainMap(1, 1, 1));
        assertTrue(withGain.hasGainMap());
    }

    @Test
    @DisplayName("constructor rejects bad dimensions, channels and buffers")
    void constructorValidates() {
        assertThrows(IllegalArgumentException.class,
            () -> new HdrFrame.GainMap(0, 4, 1, new float[4], "{}"));
        assertThrows(IllegalArgumentException.class,
            () -> new HdrFrame.GainMap(2, 2, 2, new float[8], "{}"));
        assertThrows(IllegalArgumentException.class,
            () -> new HdrFrame.GainMap(2, 2, 1, new float[3], "{}"));
        assertThrows(IllegalArgumentException.class,
            () -> new HdrFrame.GainMap(2, 2, 1, new float[4], null));
    }

    @Test
    @DisplayName("crop copies samples without aliasing")
    void cropCopies() {
        float[] pixels = {0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f};
        HdrFrame.GainMap full = new HdrFrame.GainMap(3, 3, 1, pixels, "{}");
        HdrFrame.GainMap cropped = full.crop(1, 1, 2, 2);
        assertEquals(2, cropped.width());
        assertEquals(2, cropped.height());
        assertArrayEquals(new float[]{4f, 5f, 7f, 8f}, cropped.pixels());
        assertEquals("{}", cropped.metadataJson());
        cropped.pixels()[0] = 42f;
        assertEquals(4f, full.pixels()[4]);
    }

    @Test
    @DisplayName("crop rejects out-of-bounds regions")
    void cropRejectsOutOfBounds() {
        HdrFrame.GainMap full = gainMap(3, 3, 1);
        assertThrows(IllegalArgumentException.class, () -> full.crop(2, 2, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> full.crop(-1, 0, 2, 2));
    }

    @Test
    @DisplayName("fromBufferedImage preserves channels and round-trips")
    void fromBufferedImageRoundTrip() {
        BufferedImage gray = new BufferedImage(4, 4, BufferedImage.TYPE_BYTE_GRAY);
        HdrFrame.GainMap grayMap = HdrFrame.GainMap.fromBufferedImage(gray, "{}");
        assertEquals(1, grayMap.channels());
        assertEquals(4, grayMap.toBufferedImage().getWidth());

        BufferedImage rgb = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        rgb.setRGB(1, 1, 0x804020);
        HdrFrame.GainMap rgbMap = HdrFrame.GainMap.fromBufferedImage(rgb, "{}");
        assertEquals(3, rgbMap.channels());
        // 0x80 / 255 in the red channel of pixel (1, 1) survives.
        assertEquals(0x80 / 255.0f, rgbMap.pixels()[(1 * 4 + 1) * 3], 1e-6f);
    }
}
