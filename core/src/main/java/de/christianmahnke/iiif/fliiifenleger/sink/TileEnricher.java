// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.sink;

import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;

import java.util.Map;

/**
 * Contributes per-tile entries to the metadata map the {@code Tiler} hands to
 * each {@link TileSink}.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} (register
 * with {@code @AutoService(TileEnricher.class)}) and invoked for every
 * generated tile and size rendition.  This keeps domain-specific enrichment
 * (tile regions, UltraHDR gain-map crops, …) out of the core tiling loop:
 * core ships {@link RegionTileEnricher}, the {@code ultrahdr} module adds the
 * gain-map crop.
 *
 * <p>Contract: enrichers must be stateless and thread-safe (the {@code Tiler}
 * generates tiles concurrently) and must write disjoint keys — invocation
 * order is unspecified.
 */
public interface TileEnricher {

    /**
     * Enriches the metadata for one tile.
     *
     * @param source   The tiled image source (primary dimensions via
     *                 {@link ImageSource#getWidth}/{@link ImageSource#getHeight}).
     * @param x        Tile region X in source-image pixels.
     * @param y        Tile region Y in source-image pixels.
     * @param w        Tile region width in source-image pixels.
     * @param h        Tile region height in source-image pixels.
     * @param scale    Scale factor (1 = full resolution).
     * @param metadata The mutable per-tile metadata map to enrich.
     */
    void enrich(ImageSource source, int x, int y, int w, int h, int scale,
                Map<String, Object> metadata);
}
