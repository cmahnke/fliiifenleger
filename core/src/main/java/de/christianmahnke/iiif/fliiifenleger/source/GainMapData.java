// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.source;

import java.io.Serializable;

/**
 * The gain map data of an UltraHDR image, carried alongside the primary
 * image through the tiling pipeline.
 *
 * <p>The gain map is a plain JPEG image at a lower resolution than the
 * primary (the ratio is {@code primaryWidth / gainmapWidth}).  The metadata
 * is the ISO 21496-1 gain map metadata as JSON (camelCase field names, as
 * produced by the ultrahdr WASM codec) and is global to the image — tiles
 * preserve it verbatim.
 *
 * @param gainmapJpeg   Raw gain map JPEG bytes.
 * @param metadataJson  ISO 21496-1 gain map metadata as JSON.
 * @param width         Gain map image width in pixels.
 * @param height        Gain map image height in pixels.
 */
public record GainMapData(byte[] gainmapJpeg, String metadataJson,
                          int width, int height) implements Serializable {

    /**
     * Metadata map key (value: {@link java.awt.image.BufferedImage}) under
     * which the {@code Tiler} passes the cropped gain map tile to the sink.
     */
    public static final String META_IMAGE = "gainmap.image";

    /**
     * Metadata map key (value: {@link String}) under which the {@code Tiler}
     * passes the gain map metadata JSON to the sink.
     */
    public static final String META_METADATA = "gainmap.metadata";

    /**
     * Metadata map key (value: {@code int}) — the gain map region X in
     * gain-map coordinates.
     */
    public static final String META_X = "gainmap.x";

    /** Metadata map key (value: {@code int}) — gain map region Y. */
    public static final String META_Y = "gainmap.y";

    /** Metadata map key (value: {@code int}) — gain map region width. */
    public static final String META_W = "gainmap.w";

    /** Metadata map key (value: {@code int}) — gain map region height. */
    public static final String META_H = "gainmap.h";
}
