// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.source;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * An HDR image frame: full-range float pixels plus the color description
 * needed to interpret them.
 *
 * <p>This is the transport type for HDR content between {@link HdrSource}
 * implementations and HDR-aware sinks.  Pixels are interleaved
 * {@code width * height * channels} 32-bit floats ({@code R}, {@code RGB},
 * or straight-alpha {@code RGBA}); values are scene- or display-referred
 * per {@link #transfer}, not clamped.
 *
 * <p>There is deliberately no standard Java equivalent: {@code java.awt}
 * cannot express PQ/HLG transfer functions (see {@code ColorSpace}), and
 * every imaging library reads {@code BufferedImage} contents as SDR.
 * Following the TwelveMonkeys HDR precedent, the SDR-compatible view is
 * therefore a separate, explicit rendition ({@link #toBufferedImage},
 * clamp + sRGB) rather than a smuggled exotic raster — legacy consumers
 * can never silently misread HDR floats as SDR bytes.
 */
public record HdrFrame(int width, int height, int channels, float[] pixels,
                       TransferFunction transfer, Primaries primaries) {

    /** Metadata-map key for the frame object itself (see {@link HdrSource}). */
    public static final String META_FRAME = "hdr.frame";

    /** Metadata-map key for the transfer function name. */
    public static final String META_TRANSFER = "hdr.transfer";

    /** Metadata-map key for the primaries name. */
    public static final String META_PRIMARIES = "hdr.primaries";

    /** Opto-electronic transfer function of the pixel values. */
    public enum TransferFunction {
        /** sRGB EOTF (typical SDR rendition content). */
        SRGB,
        /** Scene-linear light. */
        LINEAR,
        /** SMPTE ST 2084 perceptual quantizer. */
        PQ,
        /** Hybrid log-gamma (ARIB STD-B67). */
        HLG,
        /** Unrecognized or undeclared transfer. */
        UNKNOWN
    }

    /** RGB primaries / gamut of the pixel values. */
    public enum Primaries {
        /** Rec.709 / sRGB primaries. */
        BT709,
        /** Rec.2100 primaries. */
        BT2020,
        /** Display P3 primaries. */
        DISPLAY_P3,
        /** Unrecognized or undeclared primaries. */
        UNKNOWN
    }

    public HdrFrame {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                "HDR frame dimensions must be positive, got " + width + "x" + height);
        }
        if (channels != 1 && channels != 3 && channels != 4) {
            throw new IllegalArgumentException(
                "HDR frame channels must be 1, 3, or 4, got " + channels);
        }
        if (pixels == null || pixels.length != width * height * channels) {
            throw new IllegalArgumentException(
                "HDR pixel buffer must hold width*height*channels floats");
        }
        if (transfer == null || primaries == null) {
            throw new IllegalArgumentException("Transfer and primaries must not be null");
        }
    }

    /**
     * Render an 8-bit SDR view of this frame (clamp to [0, 1], sRGB encode).
     *
     * <p>This is intentionally lossy and simple — a photographic tone
     * mapper may replace it later.  Grayscale, RGB and straight-alpha RGBA
     * map to {@code TYPE_BYTE_GRAY}, {@code TYPE_3BYTE_BGR} and
     * {@code TYPE_4BYTE_ABGR} respectively, mirroring the JXL bridge.
     *
     * @return A new 8-bit {@link BufferedImage}.
     */
    public BufferedImage toBufferedImage() {
        if (channels == 1) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (encode(pixels[i]) * 255.0f + 0.5f);
            }
            return image;
        }
        BufferedImage image = new BufferedImage(width, height,
            channels == 4 ? BufferedImage.TYPE_4BYTE_ABGR : BufferedImage.TYPE_3BYTE_BGR);
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int i = 0, n = width * height; i < n; i++) {
            if (channels == 3) {
                data[3 * i]     = (byte) (encode(pixels[3 * i + 2]) * 255.0f + 0.5f);
                data[3 * i + 1] = (byte) (encode(pixels[3 * i + 1]) * 255.0f + 0.5f);
                data[3 * i + 2] = (byte) (encode(pixels[3 * i]) * 255.0f + 0.5f);
            } else { // channels == 4 (compact constructor rejects the rest)
                data[4 * i]     = (byte) (encode(pixels[4 * i + 3]) * 255.0f + 0.5f);
                data[4 * i + 1] = (byte) (encode(pixels[4 * i + 2]) * 255.0f + 0.5f);
                data[4 * i + 2] = (byte) (encode(pixels[4 * i + 1]) * 255.0f + 0.5f);
                data[4 * i + 3] = (byte) (encode(pixels[4 * i]) * 255.0f + 0.5f);
            }
        }
        return image;
    }

    /**
     * Wrap an 8-bit SDR image as a frame ({@code SRGB}/{@code BT709}).
     *
     * @param image Source image (any type readable via {@code getRGB}).
     * @return Equivalent frame with values in [0, 1].
     */
    public static HdrFrame fromBufferedImage(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        float[] px = new float[w * h * 3];
        int[] rgb = image.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < rgb.length; i++) {
            px[3 * i]     = ((rgb[i] >> 16) & 0xFF) / 255.0f;
            px[3 * i + 1] = ((rgb[i] >> 8) & 0xFF) / 255.0f;
            px[3 * i + 2] = (rgb[i] & 0xFF) / 255.0f;
        }
        return new HdrFrame(w, h, 3, px, TransferFunction.SRGB, Primaries.BT709);
    }

    /**
     * Placeholder rendition curve: clamp NaN to 0, infinities to the range
     * ends, linear values to [0, 1].  A photographic tone mapper (Reinhard,
     * ACES) may replace this once HDR-aware sinks need better previews —
     * the method boundary keeps that change local.
     */
    static float encode(float v) {
        if (Float.isNaN(v)) {
            return 0.0f;
        }
        return Math.min(1.0f, Math.max(0.0f, v));
    }
}
