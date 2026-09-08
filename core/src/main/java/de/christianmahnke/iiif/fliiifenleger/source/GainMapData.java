/**
 * Fliiifenleger
 * Copyright (C) 2026  Christian Mahnke
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

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
