// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.source;

/**
 * Capability interface for {@link ImageSource} implementations that carry an
 * UltraHDR gain map alongside the primary image.
 *
 * <p>The {@code Tiler} checks for this capability; when present, each tile
 * is accompanied by the correspondingly cropped gain map (passed through the
 * metadata map, see the {@link GainMapData} key constants), which UltraHDR
 * sinks re-embed into the tiles.
 */
public interface GainMapSource {

    /**
     * @return The gain map data of the source image.
     * @throws ImageSourceException if the gain map data cannot be provided.
     */
    GainMapData getGainMap() throws ImageSourceException;
}
