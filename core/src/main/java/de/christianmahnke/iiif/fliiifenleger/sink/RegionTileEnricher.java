// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.sink;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;

import java.util.Map;

/**
 * Records the tile's region in source-image coordinates so sinks that care
 * about provenance (e.g. the C2PA sink) can describe which part of the
 * original image a tile depicts.
 */
@AutoService(TileEnricher.class)
public class RegionTileEnricher implements TileEnricher {

    @Override
    public void enrich(ImageSource source, int x, int y, int w, int h, int scale,
                       Map<String, Object> metadata) {
        metadata.put("iiif.region.x", x);
        metadata.put("iiif.region.y", y);
        metadata.put("iiif.region.w", w);
        metadata.put("iiif.region.h", h);
        metadata.put("iiif.region.scale", scale);
    }
}
