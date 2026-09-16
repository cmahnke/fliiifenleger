// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSinkException;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSourceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tiler info.json extension merging")
class TilerInfoExtensionTest {

    static class StubSource implements ImageSource {
        private final URL url;

        StubSource() {
            try {
                url = URI.create("file:///tmp/page011.jpg").toURL();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String getName() {
            return "stub";
        }

        @Override
        public BufferedImage getImage() {
            return new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        }

        @Override
        public URL getUrl() {
            return url;
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
            return 48;
        }

        @Override
        public BufferedImage crop(int x, int y, int width, int height, double scale) throws ImageSourceException {
            return new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB);
        }

        @Override
        public Map<String, Object> getMetadata() {
            return Map.of();
        }
    }

    static class StubSink implements TileSink {
        private final InfoExtension extension;

        StubSink(InfoExtension extension) {
            this.extension = extension;
        }

        @Override
        public void saveTile(OutputStream outputStream, BufferedImage image, Map<String, Object> metadata) throws TileSinkException {
        }

        @Override
        public String getFormatExtension() {
            return "jpg";
        }

        @Override
        public String getName() {
            return "stub";
        }

        @Override
        public InfoExtension getInfoJsonExtension(ImageInfo.IIIFVersion version) {
            return extension;
        }
    }

    private static ImageInfo info(ImageInfo.IIIFVersion version) {
        return new ImageInfo(new StubSource(), 32, 32, 2, "http://localhost:8887/iiif/", version);
    }

    /** Example extension URIs (merge logic is URI-agnostic). */
    private static final String EXT_PROFILE = "https://example.org/ext/";
    private static final String EXT_CONTEXT = "https://example.org/ext/context.json";
    private static final String OTHER_FEATURE = "https://example.org/other/";

    @Test
    @DisplayName("V3 merges contexts, services and extraFeatures with IIIF context last")
    void v3MergesAll() {
        Map<String, Object> service = Map.of("id", EXT_PROFILE, "type", "Service",
                "profile", EXT_PROFILE, "trustAnchor", "https://example.org/trust");
        TileSink.InfoExtension ext = new TileSink.InfoExtension(
                List.of(EXT_CONTEXT), List.of(service), List.of(EXT_PROFILE));
        Map<String, Object> json = Tiler.buildInfoJson(info(ImageInfo.IIIFVersion.V3), ext);

        assertEquals(List.of(EXT_CONTEXT, "http://iiif.io/api/image/3/context.json"), json.get("@context"));
        assertEquals(List.of(EXT_PROFILE), json.get("extraFeatures"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) json.get("service");
        assertEquals(1, services.size());
        assertEquals("https://example.org/trust", services.get(0).get("trustAnchor"));
    }

    @Test
    @DisplayName("V2 maps features into the embedded profile supports list")
    void v2MapsFeaturesToSupports() {
        TileSink.InfoExtension ext = new TileSink.InfoExtension(
                List.of(), List.of(), List.of(OTHER_FEATURE));
        Map<String, Object> json = Tiler.buildInfoJson(info(ImageInfo.IIIFVersion.V2), ext);

        assertEquals("http://iiif.io/api/image/2/context.json", json.get("@context"));
        @SuppressWarnings("unchecked")
        List<Object> profile = (List<Object>) json.get("profile");
        assertEquals("http://iiif.io/api/image/2/level2.json", profile.get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> embedded = (Map<String, Object>) profile.get(1);
        assertEquals(List.of(OTHER_FEATURE), embedded.get("supports"));
    }

    @Test
    @DisplayName("V2 with extension contexts fails (no place for namespaced options)")
    void v2ContextsFail() {
        TileSink.InfoExtension ext = new TileSink.InfoExtension(
                List.of(EXT_CONTEXT), List.of(), List.of(EXT_PROFILE));
        assertThrows(IllegalArgumentException.class,
                () -> Tiler.buildInfoJson(info(ImageInfo.IIIFVersion.V2), ext));
    }

    @Test
    @DisplayName("empty extension leaves the base document untouched")
    void emptyExtensionUntouched() {
        Map<String, Object> base = info(ImageInfo.IIIFVersion.V3).toJson();
        Map<String, Object> merged = Tiler.buildInfoJson(info(ImageInfo.IIIFVersion.V3), TileSink.InfoExtension.empty());
        assertEquals(base.keySet(), merged.keySet());
        for (String key : base.keySet()) {
            if ("tiles".equals(key)) {
                continue; // Tile has no equals(); count is what matters here.
            }
            assertEquals(base.get(key), merged.get(key), "entry " + key);
        }
    }

    @Test
    @DisplayName("ImageInfo exposes version and identifier")
    void imageInfoExposesVersion() {
        assertEquals(ImageInfo.IIIFVersion.V3, info(ImageInfo.IIIFVersion.V3).getVersion());
        assertEquals("http://localhost:8887/iiif/", info(ImageInfo.IIIFVersion.V2).getIdentifier());
    }
}
