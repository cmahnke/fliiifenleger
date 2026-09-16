// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import de.christianmahnke.iiif.fliiifenleger.source.HdrFrame;

import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Derives an ISO 21496-1 gain map from a true-HDR frame (e.g. JXL PQ/HLG)
 * that carries no gain map of its own.
 *
 * <p>This keeps gain maps transparent: no source needs to know about them —
 * when the {@link UltraHdrTileSink} receives an {@link HdrFrame} without a
 * gain map, it derives one here before WASM assembly instead of failing.
 *
 * <p>The forward convention mirrors {@code ultrahdr-core}'s
 * {@code compute_gainmap} (vendored reference, {@code ultrahdr-core 0.4.1}):
 * per-channel gain {@code (hdr + altOff) / (sdr + baseOff)} with
 * {@code 1/64} offsets, clamp to {@code [1.0, maxBoost]}, normalize in log
 * domain, gamma {@code 1.0}, 8-bit encode. Deviations are deliberate and
 * documented: the ceiling is adaptive ({@code max(6.0, tilePeak)} instead
 * of the reference's fixed {@code 6.0}, so nothing ever clips), the gain
 * map is always multi-channel RGB, and sampling is a box average in linear
 * light rather than center-sampling.
 *
 * <p>Scale handling: HDR values are absolute while the SDR base is relative,
 * so HDR linear light is normalized by the diffuse-white anchor (100 nits
 * for PQ; HLG reference white for HLG; 1.0 for linear/sRGB) before the
 * ratio. The SDR base is always the pipeline's delegate-rendered tile —
 * the same bytes the WASM encoder muxes — so reconstruction matches by
 * construction. No gamut conversion is performed (per-channel gains in the
 * frame's native primaries).
 */
public final class HdrGainMapDeriver {

    /** Gain map subsampling: one gain sample per 4×4 tile pixels. */
    public static final int SCALE_FACTOR = 4;

    /** Encoding gamma (linear mapping, mirroring the reference default). */
    public static final double GAMMA = 1.0;

    /** Linear-domain offsets avoiding division by zero (reference defaults). */
    public static final double BASE_OFFSET = 1.0 / 64.0;

    /** Linear-domain offsets avoiding division by zero (reference defaults). */
    public static final double ALTERNATE_OFFSET = 1.0 / 64.0;

    /** Minimum boost (linear): 1.0 means SDR areas stay untouched. */
    public static final double MIN_BOOST = 1.0;

    /** Adaptive ceiling floor (linear): peaks below never clip. */
    public static final double MAX_BOOST_FLOOR = 6.0;

    private HdrGainMapDeriver() {
    }

    /**
     * Derived gain map plus its ISO 21496-1 metadata JSON (camelCase, matching
     * the WASM codec's {@code MetadataJson} schema).
     */
    public record Derivation(HdrFrame.GainMap gainMap, String metadataJson) {
    }

    /**
     * Derives the gain map for one tile.
     *
     * @param frame   Full-image HDR frame (must not carry usable gain data —
     *                callers branch on {@link HdrFrame#hasGainMap()} first).
     * @param x       Tile region X in frame pixels (HDR-space coords: apply
     *                any primary/gainmap ratio mapping before calling).
     * @param y       Tile region Y in frame pixels.
     * @param w       Tile region width in frame pixels.
     * @param h       Tile region height in frame pixels.
     * @param sdrTile Pipeline SDR rendition of the same region (the exact
     *                image the delegate sink renders: sizes may differ from
     *                {@code w,h} at scaled levels).
     * @return Gain map at {@code ceil(tile/scale)} resolution plus metadata.
     * @throws IllegalArgumentException for {@code UNKNOWN} transfer
     *         functions (no defined EOTF to linearize with).
     */
    public static Derivation derive(HdrFrame frame, int x, int y, int w, int h,
                                    BufferedImage sdrTile) {
        if (frame == null || sdrTile == null) {
            throw new IllegalArgumentException("Frame and SDR tile must not be null");
        }
        if (x < 0 || y < 0 || w <= 0 || h <= 0
                || x + w > frame.width() || y + h > frame.height()) {
            throw new IllegalArgumentException(
                "HDR region [" + x + "," + y + " " + w + "x" + h
                + "] out of bounds for " + frame.width() + "x" + frame.height());
        }
        int tw = sdrTile.getWidth();
        int th = sdrTile.getHeight();
        if (tw <= 0 || th <= 0) {
            throw new IllegalArgumentException("SDR tile has no pixels");
        }

        // HDR crop (linear light, diffuse-normalized) downsampled to the
        // SDR tile grid, plus the linearized SDR base on the same grid.
        float[] hdr = downsampleLinear(frame, x, y, w, h, tw, th);
        float[] sdr = linearizeSdrTile(sdrTile);

        double[] peak = {0.0, 0.0, 0.0};
        double[] boost = new double[tw * th * 3];
        for (int i = 0; i < tw * th; i++) {
            for (int c = 0; c < 3; c++) {
                double b = (hdr[3 * i + c] + ALTERNATE_OFFSET)
                         / (sdr[3 * i + c] + BASE_OFFSET);
                boost[3 * i + c] = b;
                peak[c] = Math.max(peak[c], b);
            }
        }
        double ceiling = Math.max(MAX_BOOST_FLOOR,
            Math.max(peak[0], Math.max(peak[1], peak[2])));
        double logMin = Math.log(MIN_BOOST);
        double logRange = Math.log(ceiling) - logMin;

        // Center-sample SCALE×SCALE blocks → subsampled gain plane.
        int gw = (tw + SCALE_FACTOR - 1) / SCALE_FACTOR;
        int gh = (th + SCALE_FACTOR - 1) / SCALE_FACTOR;
        float[] gainPixels = new float[gw * gh * 3];
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                int sx = Math.min(gx * SCALE_FACTOR + SCALE_FACTOR / 2, tw - 1);
                int sy = Math.min(gy * SCALE_FACTOR + SCALE_FACTOR / 2, th - 1);
                for (int c = 0; c < 3; c++) {
                    double clamped = Math.min(Math.max(boost[(sy * tw + sx) * 3 + c],
                        MIN_BOOST), ceiling);
                    double normalized = (Math.log(clamped) - logMin) / logRange;
                    gainPixels[(gy * gw + gx) * 3 + c] =
                        (float) Math.pow(normalized, GAMMA);
                }
            }
        }

        double[] metaMax = new double[3];
        double[] metaMin = new double[3];
        for (int c = 0; c < 3; c++) {
            metaMax[c] = Math.log(Math.min(Math.max(peak[c], MIN_BOOST), ceiling)) / Math.log(2);
            metaMin[c] = 0.0;
        }
        double headroom = Math.log(Math.max(MAX_BOOST_FLOOR,
            Math.max(peak[0], Math.max(peak[1], peak[2])))) / Math.log(2);
        String metadataJson = metadataJson(metaMax, metaMin, headroom);
        return new Derivation(
            new HdrFrame.GainMap(gw, gh, 3, gainPixels, metadataJson), metadataJson);
    }

    // ── Transfer functions ──────────────────────────────────────────────────

    /**
     * Linearizes one SDR code value (sRGB EOTF).
     */
    static double linearizeSrgb(double v) {
        if (v <= 0.04045) {
            return v / 12.92;
        }
        return Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /**
     * PQ EOTF (BT.2100): code {@code v} in [0, 1] → absolute linear light
     * normalized to 10 000 nits (1.0 = peak).
     */
    static double pqEotf(double v) {
        final double m1 = 2610.0 / 16384.0;
        final double m2 = 2523.0 / 4096.0 * 128.0;
        final double c1 = 3424.0 / 4096.0;
        final double c2 = 2413.0 / 4096.0 * 32.0;
        final double c3 = 2392.0 / 4096.0 * 32.0;
        double vp = Math.pow(Math.max(v, 0.0), 1.0 / m2);
        double num = Math.max(vp - c1, 0.0);
        double den = c2 - c3 * vp;
        if (den <= 0.0) {
            return 1.0;
        }
        return Math.pow(num / den, 1.0 / m1);
    }

    /**
     * HLG inverse OETF (BT.2100): code {@code v} → scene-linear signal.
     */
    static double hlgOetfInverse(double v) {
        final double a = 0.17883277;
        final double b = 0.28466892;
        final double c = 0.55991073;
        if (v <= 0.5) {
            return (v * v) / 3.0;
        }
        return (Math.exp((v - c) / a) + b) / 12.0;
    }

    /**
     * HLG OOTF gamma for the nominal 1000-nit display
     * ({@code 1.2 + 0.42 * log10(Lw/1000)}).
     */
    static double hlgSystemGamma() {
        return 1.2;
    }

    /**
     * Rec.709 luma coefficients (also used for Display P3, which shares
     * the D65 white point adoption here; BT.2020 has its own set).
     */
    static double[] lumaCoefficients(HdrFrame.Primaries primaries) {
        if (primaries == HdrFrame.Primaries.BT2020) {
            return new double[]{0.2627, 0.6780, 0.0593};
        }
        return new double[]{0.2126, 0.7152, 0.0722};
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * Crops the HDR region and box-averages it in linear light onto the
     * {@code tw × th} grid (3-channel RGB; gray frames replicate, alpha is
     * dropped). Values are diffuse-normalized so SDR white ≈ 1.0.
     */
    private static float[] downsampleLinear(HdrFrame frame, int x, int y, int w, int h,
                                            int tw, int th) {
        double anchor = diffuseAnchor(frame.transfer());
        boolean hlg = frame.transfer() == HdrFrame.TransferFunction.HLG;
        double[] luma = lumaCoefficients(frame.primaries());
        float[] src = frame.pixels();
        int srcChannels = frame.channels();
        float[] out = new float[tw * th * 3];
        for (int ty = 0; ty < th; ty++) {
            int y0 = y + (int) ((long) ty * h / th);
            int y1 = y + (int) ((long) (ty + 1) * h / th);
            for (int tx = 0; tx < tw; tx++) {
                int x0 = x + (int) ((long) tx * w / tw);
                int x1 = x + (int) ((long) (tx + 1) * w / tw);
                double[] acc = {0.0, 0.0, 0.0};
                int n = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int base = (sy * frame.width() + sx) * srcChannels;
                        // HLG accumulates absolute OETF^-1 signals (the OOTF
                        // and anchor apply below); other transfers normalize
                        // per sample.
                        double norm = hlg ? 1.0 : anchor;
                        double r;
                        double g;
                        double b;
                        if (srcChannels == 1) {
                            r = g = b = toLinear(frame.transfer(), src[base], norm);
                        } else {
                            r = toLinear(frame.transfer(), src[base], norm);
                            g = toLinear(frame.transfer(), src[base + 1], norm);
                            b = toLinear(frame.transfer(), src[base + 2], norm);
                        }
                        acc[0] += r;
                        acc[1] += g;
                        acc[2] += b;
                        n++;
                    }
                }
                n = Math.max(n, 1);
                if (hlg) {
                    // BT.2100 OOTF on the cell-average signal, then anchor.
                    double ys = (acc[0] * luma[0] + acc[1] * luma[1] + acc[2] * luma[2]) / n;
                    double ootf = Math.pow(Math.max(ys, 0.0), hlgSystemGamma() - 1.0);
                    for (int c = 0; c < 3; c++) {
                        out[(ty * tw + tx) * 3 + c] = (float) (acc[c] / n * ootf / anchor);
                    }
                } else {
                    for (int c = 0; c < 3; c++) {
                        out[(ty * tw + tx) * 3 + c] = (float) (acc[c] / n);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Diffuse-white anchor: absolute-linear value that maps to SDR 1.0
     * (100 nits for PQ; HLG reference white at the nominal display;
     * 1.0 for relative encodings).
     */
    static double diffuseAnchor(HdrFrame.TransferFunction transfer) {
        switch (transfer) {
            case PQ:
                return 0.01;
            case HLG: {
                double ref = hlgOetfInverse(0.75);
                return 1000.0 * Math.pow(ref, hlgSystemGamma() - 1.0) * ref / 10000.0;
            }
            default:
                return 1.0;
        }
    }

    private static double toLinear(HdrFrame.TransferFunction transfer, double code,
                                   double anchor) {
        switch (transfer) {
            case PQ:
                return pqEotf(code) / anchor;
            case HLG:
                return hlgOetfInverse(code) / anchor;
            case SRGB:
                return linearizeSrgb(Math.min(Math.max(code, 0.0), 1.0)) / anchor;
            case LINEAR:
                return code / anchor;
            default:
                throw new IllegalArgumentException(
                    "Cannot derive a gain map: unsupported transfer function " + transfer);
        }
    }

    private static float[] linearizeSdrTile(BufferedImage tile) {
        int w = tile.getWidth();
        int h = tile.getHeight();
        int[] rgb = tile.getRGB(0, 0, w, h, null, 0, w);
        float[] out = new float[w * h * 3];
        for (int i = 0; i < rgb.length; i++) {
            out[3 * i]     = (float) linearizeSrgb(((rgb[i] >> 16) & 0xFF) / 255.0);
            out[3 * i + 1] = (float) linearizeSrgb(((rgb[i] >> 8) & 0xFF) / 255.0);
            out[3 * i + 2] = (float) linearizeSrgb((rgb[i] & 0xFF) / 255.0);
        }
        return out;
    }

    /**
     * Builds the ISO 21496-1 metadata JSON (camelCase, WASM codec schema):
     * min/max in log2 stops, unit gamma, {@code 1/64} offsets.
     */
    static String metadataJson(double[] gainMax, double[] gainMin, double headroomStops) {
        return String.format(Locale.ROOT,
            "{\"gainMapMax\": [%.6f, %.6f, %.6f], "
            + "\"gainMapMin\": [%.6f, %.6f, %.6f], "
            + "\"gamma\": [1.0, 1.0, 1.0], "
            + "\"baseOffset\": [0.015625, 0.015625, 0.015625], "
            + "\"alternateOffset\": [0.015625, 0.015625, 0.015625], "
            + "\"baseHdrHeadroom\": 0.0, "
            + "\"alternateHdrHeadroom\": %.6f, "
            + "\"useBaseColorSpace\": true, "
            + "\"backwardDirection\": false}",
            gainMax[0], gainMax[1], gainMax[2],
            gainMin[0], gainMin[1], gainMin[2],
            headroomStops);
    }
}
