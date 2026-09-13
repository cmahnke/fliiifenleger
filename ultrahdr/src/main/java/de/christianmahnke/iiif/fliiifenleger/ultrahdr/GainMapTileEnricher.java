// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.sink.TileEnricher;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;

/**
 * Enriches the per-tile metadata map with the cropped gain map tile when the
 * image source carries an UltraHDR gain map (see {@link GainMapSource}).
 *
 * <p>The gain map is cropped with the region mapped from primary-image
 * coordinates through the {@code primaryWidth / gainmapWidth} ratio,
 * rounding outwards (floor for the start, ceiling for the end) so the
 * crop always fully covers the tile region; ISO 21496-1 readers scale
 * the gain map back to the primary dimensions, so slight over-coverage
 * at tile edges is harmless.
 *
 * <p>Stateless and thread-safe: tiles are generated concurrently.
 */
@AutoService(TileEnricher.class)
public class GainMapTileEnricher implements TileEnricher {

    private static final Logger log = LoggerFactory.getLogger(GainMapTileEnricher.class);

    @Override
    public void enrich(ImageSource source, int x, int y, int w, int h, int scale,
                       Map<String, Object> metadata) {
        if (!(source instanceof GainMapSource gainMapSource)) {
            return;
        }
        try {
            GainMapData gainMap = gainMapSource.getGainMap();
            if (gainMap == null) {
                return;
            }

            double ratioX = (double) source.getWidth() / gainMap.width();
            double ratioY = (double) source.getHeight() / gainMap.height();

            int gx0 = (int) Math.floor(x / ratioX);
            int gy0 = (int) Math.floor(y / ratioY);
            int gx1 = (int) Math.ceil((x + w) / ratioX);
            int gy1 = (int) Math.ceil((y + h) / ratioY);
            gx0 = Math.max(0, Math.min(gx0, gainMap.width() - 1));
            gy0 = Math.max(0, Math.min(gy0, gainMap.height() - 1));
            gx1 = Math.max(gx0 + 1, Math.min(gx1, gainMap.width()));
            gy1 = Math.max(gy0 + 1, Math.min(gy1, gainMap.height()));

            BufferedImage gainmapImage = ImageIO.read(new ByteArrayInputStream(gainMap.gainmapJpeg()));
            if (gainmapImage == null) {
                log.warn("Gain map image could not be decoded; tile will not carry a gain map");
                return;
            }
            // A copy, not a subimage view: the source image may be reused for
            // the next tile while this one is encoded asynchronously.
            BufferedImage cropped = gainmapImage.getSubimage(gx0, gy0, gx1 - gx0, gy1 - gy0);
            BufferedImage copy = new BufferedImage(cropped.getWidth(), cropped.getHeight(), cropped.getType());
            Graphics2D g = copy.createGraphics();
            try {
                g.drawImage(cropped, 0, 0, null);
            } finally {
                g.dispose();
            }

            metadata.put(GainMapData.META_IMAGE, copy);
            metadata.put(GainMapData.META_METADATA, gainMap.metadataJson());
            metadata.put(GainMapData.META_X, gx0);
            metadata.put(GainMapData.META_Y, gy0);
            metadata.put(GainMapData.META_W, gx1 - gx0);
            metadata.put(GainMapData.META_H, gy1 - gy0);
        } catch (Exception e) {
            log.warn("Gain map crop failed; tile will not carry a gain map: {}", e.getMessage());
        }
    }
}
