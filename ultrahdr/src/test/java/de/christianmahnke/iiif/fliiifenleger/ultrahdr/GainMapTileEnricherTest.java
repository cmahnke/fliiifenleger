// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSourceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GainMapTileEnricher")
class GainMapTileEnricherTest {

    /** Minimal ImageSource with a fixed size and an optional gain map. */
    static class StubSource implements ImageSource, GainMapSource {
        private final int width;
        private final int height;
        private final GainMapData gainMap;

        StubSource(int width, int height, GainMapData gainMap) {
            this.width = width;
            this.height = height;
            this.gainMap = gainMap;
        }

        @Override
        public String getName() {
            return "stub";
        }

        @Override
        public BufferedImage getImage() {
            return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        }

        @Override
        public URL getUrl() {
            try {
                return new URL("file:///tmp/stub.jpg");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void load(URL url) {
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public BufferedImage crop(int x, int y, int width, int height, double scale) {
            return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        }

        @Override
        public Map<String, Object> getMetadata() {
            return Map.of();
        }

        @Override
        public GainMapData getGainMap() {
            return gainMap;
        }
    }

    static class PlainSource extends StubSource {
        PlainSource() {
            super(64, 64, null);
        }
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.GRAY);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static StubSource gainMapSource() throws Exception {
        // 64×64 primary with a 32×32 gain map (2:1 ratio).
        GainMapData gainMap = new GainMapData(jpeg(32, 32), "{}", 32, 32);
        return new StubSource(64, 64, gainMap);
    }

    @Test
    @DisplayName("full-region tile crops the whole gain map")
    void fullRegionCropsWholeGainMap() throws Exception {
        GainMapTileEnricher enricher = new GainMapTileEnricher();
        Map<String, Object> metadata = new HashMap<>();
        enricher.enrich(gainMapSource(), 0, 0, 64, 64, 1, metadata);

        assertThat(metadata.get(GainMapData.META_X)).isEqualTo(0);
        assertThat(metadata.get(GainMapData.META_Y)).isEqualTo(0);
        assertThat(metadata.get(GainMapData.META_W)).isEqualTo(32);
        assertThat(metadata.get(GainMapData.META_H)).isEqualTo(32);
        assertThat(metadata.get(GainMapData.META_METADATA)).isEqualTo("{}");
        assertThat(metadata.get(GainMapData.META_IMAGE)).isInstanceOf(BufferedImage.class);
        BufferedImage cropped = (BufferedImage) metadata.get(GainMapData.META_IMAGE);
        assertThat(cropped.getWidth()).isEqualTo(32);
        assertThat(cropped.getHeight()).isEqualTo(32);
    }

    @Test
    @DisplayName("partial tile maps through the primary/gainmap ratio")
    void partialTileMapsThroughRatio() throws Exception {
        GainMapTileEnricher enricher = new GainMapTileEnricher();
        Map<String, Object> metadata = new HashMap<>();
        enricher.enrich(gainMapSource(), 16, 16, 16, 16, 1, metadata);

        assertThat(metadata.get(GainMapData.META_X)).isEqualTo(8);
        assertThat(metadata.get(GainMapData.META_Y)).isEqualTo(8);
        assertThat(metadata.get(GainMapData.META_W)).isEqualTo(8);
        assertThat(metadata.get(GainMapData.META_H)).isEqualTo(8);
    }

    @Test
    @DisplayName("sources without a gain map leave metadata untouched")
    void nullGainMapLeavesMetadataUntouched() {
        GainMapTileEnricher enricher = new GainMapTileEnricher();
        Map<String, Object> metadata = new HashMap<>(Map.of("iiif.region.x", 0));
        enricher.enrich(new PlainSource(), 0, 0, 64, 64, 1, metadata);

        assertThat(metadata).containsExactlyEntriesOf(Map.of("iiif.region.x", 0));
    }

    @Test
    @DisplayName("sources without the GainMapSource capability leave metadata untouched")
    void plainSourceLeavesMetadataUntouched() {
        GainMapTileEnricher enricher = new GainMapTileEnricher();
        ImageSource trulyPlain = new ImageSource() {
            @Override
            public String getName() {
                return "plain";
            }

            @Override
            public BufferedImage getImage() {
                return new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            }

            @Override
            public URL getUrl() {
                try {
                    return new URL("file:///tmp/plain.jpg");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void load(URL url) {
            }

            @Override
            public int getWidth() {
                return 64;
            }

            @Override
            public int getHeight() {
                return 64;
            }

            @Override
            public BufferedImage crop(int x, int y, int width, int height, double scale) throws ImageSourceException {
                return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            }

            @Override
            public Map<String, Object> getMetadata() {
                return Map.of();
            }
        };
        Map<String, Object> metadata = new HashMap<>();
        enricher.enrich(trulyPlain, 0, 0, 64, 64, 1, metadata);

        assertThat(metadata).isEmpty();
    }
}
