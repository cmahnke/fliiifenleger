// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import de.christianmahnke.iiif.fliiifenleger.debug.IiifImageReassembler;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.Map;

/**
 * Reassembles a served UltraHDR (gain-map) IIIF endpoint into a single
 * full-resolution UltraHDR JPEG.
 *
 * <p>Every tile of an HDR endpoint is itself a complete UltraHDR JPEG
 * (primary + gain map + global metadata).  Reassembly therefore stitches
 * the tile gain maps into one full gain-map image — in the same grid
 * topology as the primary-image stitching done by
 * {@link IiifImageReassembler} — and re-encodes it together with the
 * stitched primary through the {@link GainMapCodec}.
 *
 * <p>The gain-map tiles arrive at their native (subsampled) resolution,
 * so the stitched gain-map canvas has its own dimensions; the codec's
 * ISO 21496-1 readers rescale it back to the primary on display.
 * Metadata JSON is global to the image and taken verbatim from the first
 * tile that carries a gain map.
 *
 * <p>Sources and the core reassembler stay gain-map-ignorant: this class
 * is invoked only from the CLI when the endpoint's {@code info.json}
 * advertises the HDR profile (see {@link IiifImageReassembler#isHdrEndpoint}).
 */
public class UltraHdrAssembler {

    /** Default JPEG quality for the re-encoded primary image. */
    public static final int DEFAULT_PRIMARY_QUALITY = 90;

    /** Default JPEG quality for the re-encoded full gain map. */
    public static final int DEFAULT_GAINMAP_QUALITY = 85;

    private final GainMapCodec codec;

    /**
     * @param codec The codec used to split/assemble UltraHDR tiles.  Not
     *              closed by this assembler (callers own the lifecycle).
     */
    public UltraHdrAssembler(GainMapCodec codec) {
        if (codec == null) {
            throw new IllegalArgumentException("Codec must not be null");
        }
        this.codec = codec;
    }

    /**
     * Reassembles the endpoint's tiles into a full UltraHDR JPEG.
     *
     * <p>The primary image is {@code stitchedPrimary}; {@code tiles} maps
     * each fetched tile URL (in the IIIF region form
     * {@code .../{x},{y},{w},{h}/...}, as produced by
     * {@link IiifImageReassembler}) to its raw bytes.
     *
     * @param stitchedPrimary The stitched full-resolution primary image
     *                        (as returned by the reassembler).
     * @param tiles           Tile URL → raw bytes.
     * @return The full UltraHDR JPEG bytes, or {@code null} when no tile
     *         carried a gain map (i.e. the endpoint is effectively SDR).
     * @throws UltraHdrException   on a WASM codec error.
     * @throws IllegalArgumentException on a malformed tile URL or region.
     */
    public byte[] assemble(BufferedImage stitchedPrimary, Map<String, byte[]> tiles)
            throws UltraHdrException {
        if (stitchedPrimary == null) {
            throw new IllegalArgumentException("Stitched primary image must not be null");
        }
        if (tiles == null || tiles.isEmpty()) {
            return null;
        }

        // 1. Split every tile: keep the gain-map JPEG and metadata of the
        //    UHDR tiles; plain tiles (no gain map) are skipped.  Region
        //    coordinates come from the tile URL.
        java.util.List<GainMapTile> gainTiles = new java.util.ArrayList<>();
        String metadataJson = null;
        double ratioX = 1.0;
        double ratioY = 1.0;
        for (Map.Entry<String, byte[]> entry : tiles.entrySet()) {
            byte[] bytes = entry.getValue();
            GainMapCodec.UhdrSplit split;
            try {
                split = codec.decode(bytes);
            } catch (UltraHdrException e) {
                // A plain (SDR) tile: not part of the gain map.
                continue;
            }
            if (split.gainmapJpeg() == null || split.gainmapJpeg().length == 0) {
                continue;
            }
            if (metadataJson == null) {
                metadataJson = split.metadataJson();
            }
            Region region = parseRegion(entry.getKey());
            BufferedImage gainImage = read(split.gainmapJpeg());
            gainTiles.add(new GainMapTile(region.x, region.y, region.w, region.h, gainImage));
            // All gain tiles of one image share the same subsampling ratio
            // (primary region pixels : native gain-map pixels).
            if (gainTiles.size() == 1) {
                ratioX = region.w / (double) Math.max(gainImage.getWidth(), 1);
                ratioY = region.h / (double) Math.max(gainImage.getHeight(), 1);
            }
        }
        if (gainTiles.isEmpty()) {
            // No tile carried a gain map — the endpoint is effectively SDR.
            return null;
        }

        // 2. Stitch the gain-map tiles onto a full canvas at the native
        //    (subsampled) resolution.  Each tile is placed at its primary
        //    region origin divided by the subsampling ratio, so the canvas
        //    mirrors the primary grid exactly at gain-map scale.
        int gainWidth = (int) Math.ceil(stitchedPrimary.getWidth() / ratioX);
        int gainHeight = (int) Math.ceil(stitchedPrimary.getHeight() / ratioY);
        BufferedImage gainCanvas = new BufferedImage(gainWidth, gainHeight,
            BufferedImage.TYPE_INT_RGB);
        Graphics2D g = gainCanvas.createGraphics();
        try {
            for (GainMapTile gt : gainTiles) {
                int dx = (int) Math.round(gt.x / ratioX);
                int dy = (int) Math.round(gt.y / ratioY);
                g.drawImage(gt.image, dx, dy, null);
            }
        } finally {
            g.dispose();
        }

        // 3. Re-encode the primary and the full gain map, then assemble.
        try {
            byte[] primaryJpeg = jpeg(stitchedPrimary, DEFAULT_PRIMARY_QUALITY);
            byte[] gainmapJpeg = jpeg(gainCanvas, DEFAULT_GAINMAP_QUALITY);
            return codec.encode(primaryJpeg, gainmapJpeg, metadataJson,
                                DEFAULT_PRIMARY_QUALITY, DEFAULT_GAINMAP_QUALITY);
        } catch (IOException e) {
            throw new UltraHdrException("Cannot re-encode reassembled HDR image: " + e.getMessage(), e);
        }
    }

    /** A single gain-map tile: primary region plus the native gain-map image. */
    private record GainMapTile(int x, int y, int w, int h, BufferedImage image) {
    }

    /** Parsed {@code {x},{y},{w},{h}} region from a IIIF tile URL. */
    public record Region(int x, int y, int w, int h) {
    }

    /**
     * Extracts the region coordinates from a tile URL of the form
     * {@code .../{x},{y},{w},{h}/...}.  Returns the whole image when the
     * URL has no region component (e.g. a bare {@code full/} request).
     */
    static Region parseRegion(String url) {
        String[] segments = url.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String seg = segments[i];
            if (seg.contains(",")) {
                String[] parts = seg.split(",");
                if (parts.length == 4) {
                    try {
                        return new Region(
                            Integer.parseInt(parts[0]),
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]));
                    } catch (NumberFormatException e) {
                        // Not a region — keep looking.
                    }
                }
            }
        }
        return new Region(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private static BufferedImage read(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UltraHdrException("Cannot decode gain map tile: " + e.getMessage(), e);
        }
    }

    private static byte[] jpeg(BufferedImage image, int quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", out)) {
            throw new IOException("No JPEG writer available");
        }
        return out.toByteArray();
    }
}
