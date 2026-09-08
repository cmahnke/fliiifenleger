// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/test/java/de/christianmahnke/iiif/fliiifenleger/ultrahdr/UltraHdrTileSinkTest.java
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.Tiler;
import de.christianmahnke.iiif.fliiifenleger.sink.DefaultTileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import de.christianmahnke.iiif.fliiifenleger.source.DefaultImageSource;
import de.christianmahnke.iiif.fliiifenleger.source.GainMapData;
import de.christianmahnke.iiif.fliiifenleger.source.GainMapSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link UltraHdrTileSink} and the {@link UltraHdrImageSource}:
 * an UltraHDR source is tiled and every tile must carry an integrated gain
 * map with the source's metadata.
 */
@DisplayName("UltraHdrTileSink")
class UltraHdrTileSinkTest {

    /** ISO 21496-1 metadata (camelCase) shared by all test images. */
    private static final String METADATA_JSON = """
        {
          "gainMapMax": [2.0, 2.0, 2.0],
          "gainMapMin": [0.0, 0.0, 0.0],
          "gamma": [1.0, 1.0, 1.0],
          "baseOffset": [0.015625, 0.015625, 0.015625],
          "alternateOffset": [0.015625, 0.015625, 0.015625],
          "baseHdrHeadroom": 0.0,
          "alternateHdrHeadroom": 1.0,
          "useBaseColorSpace": true,
          "backwardDirection": false
        }
        """;

    /** 64×64 primary with a 32×32 gain map (2:1 ratio). */
    private static byte[] uhdrSource;
    private static GainMapCodec codec;

    @BeforeAll
    static void setUp() throws Exception {
        codec = GainMapCodec.shared("chicory");
        uhdrSource = codec.encode(jpeg(64, 64, 0), jpeg(32, 32, 1), METADATA_JSON, 90, 85);
    }

    @AfterAll
    static void tearDown() {
        if (codec != null) {
            codec.close();
        }
    }

    private static byte[] jpeg(int w, int h, int variant) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(30 * variant, 100, 200));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(200, 30 * variant, 50));
            g.fillRect(w / 4, h / 4, w / 2, h / 2);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /** The UltraHDR source routed through the ultrahdr image source. */
    private ImageSource ultraHdrSource(Path file) throws Exception {
        UltraHdrImageSource source = new UltraHdrImageSource();
        source.load(file.toUri().toURL());
        return source;
    }

    /** Region metadata as produced by the Tiler (plus gainmap entries). */
    private Map<String, Object> tileMetadata(int x, int y, int w, int h, int scale) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("iiif.region.x", x);
        metadata.put("iiif.region.y", y);
        metadata.put("iiif.region.w", w);
        metadata.put("iiif.region.h", h);
        metadata.put("iiif.region.scale", scale);
        return metadata;
    }

    // ── UltraHdrImageSource ───────────────────────────────────────────────────

    @Test
    @DisplayName("the ultrahdr source splits primary and gain map")
    void sourceSplitsGainMap(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("source.jpg");
        Files.write(file, uhdrSource);

        ImageSource source = ultraHdrSource(file);
        BufferedImage primary = source.getImage();
        assertThat(primary.getWidth()).isEqualTo(64);
        assertThat(primary.getHeight()).isEqualTo(64);

        GainMapData gainMap = ((GainMapSource) source).getGainMap();
        assertThat(gainMap).isNotNull();
        assertThat(gainMap.width()).isEqualTo(32);
        assertThat(gainMap.height()).isEqualTo(32);
        assertThat(gainMap.metadataJson()).contains("alternateHdrHeadroom");
    }

    @Test
    @DisplayName("a plain JPEG source degrades gracefully (no gain map)")
    void plainJpegDegrades(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("plain.jpg");
        Files.write(file, jpeg(32, 32, 0));

        ImageSource source = ultraHdrSource(file);
        assertThat(source.getImage()).isNotNull();
        assertThat(((GainMapSource) source).getGainMap()).isNull();
    }

    // ── UltraHdrTileSink ──────────────────────────────────────────────────────

    @Test
    @DisplayName("saveTile assembles an UltraHDR tile with the gain map")
    void saveTileCarriesGainMap(@TempDir Path tempDir) throws Exception {
        try (UltraHdrTileSink sink = testSink()) {
            // 64×64 primary tile, full region; gainmap crop 32×32.
            Map<String, Object> metadata = tileMetadata(0, 0, 64, 64, 1);
            metadata.put(GainMapData.META_METADATA, METADATA_JSON);
            metadata.put(GainMapData.META_X, 0);
            metadata.put(GainMapData.META_Y, 0);
            metadata.put(GainMapData.META_W, 32);
            metadata.put(GainMapData.META_H, 32);
            metadata.put(GainMapData.META_IMAGE,
                         ImageIO.read(new java.io.ByteArrayInputStream(jpeg(32, 32, 1))));

            Path out = tempDir.resolve("tile.jpg");
            try (OutputStream os = Files.newOutputStream(out)) {
                sink.saveTile(os, ImageIO.read(new java.io.ByteArrayInputStream(jpeg(64, 64, 0))), metadata);
            }

            byte[] tile = Files.readAllBytes(out);
            GainMapCodec.UhdrSplit split = codec.decode(tile);
            assertThat(split.primaryJpeg()).isNotEmpty();
            assertThat(split.gainmapJpeg()).isNotEmpty();
            assertThat(split.metadataJson()).contains("alternateHdrHeadroom");
        }
    }

    @Test
    @DisplayName("tiles without gainmap metadata pass through unchanged")
    void plainTilePassesThrough(@TempDir Path tempDir) throws Exception {
        try (UltraHdrTileSink sink = testSink()) {
            Path out = tempDir.resolve("tile.jpg");
            try (OutputStream os = Files.newOutputStream(out)) {
                sink.saveTile(os, ImageIO.read(new java.io.ByteArrayInputStream(jpeg(32, 32, 0))),
                              tileMetadata(0, 0, 32, 32, 1));
            }
            byte[] tile = Files.readAllBytes(out);
            // A plain tile is NOT an UltraHDR file — decode must fail.
            assertThatThrownBy(() -> codec.decode(tile))
                .isInstanceOf(UltraHdrException.class);
        }
    }

    @Test
    @DisplayName("getName returns ultrahdr and the sink is ServiceLoader-registered")
    void sinkIsRegistered() {
        UltraHdrTileSink sink = new UltraHdrTileSink();
        assertThat(sink.getName()).isEqualTo("ultrahdr");
        assertThat(Tiler.SINK_REGISTRY).containsKey("ultrahdr");
    }

    // ── End-to-end with the Tiler ─────────────────────────────────────────────

    @Test
    @DisplayName("Tiler end-to-end: every tile of an UltraHDR source carries a gain map")
    void tilerEndToEndCarriesGainMaps(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("source.jpg");
        Files.write(sourceFile, uhdrSource);

        try {
            DefaultTileSink delegate = new DefaultTileSink();
            delegate.setOptions(Map.of("format", "jpg"));
            UltraHdrTileSink sink = new UltraHdrTileSink(codec, delegate);

            UltraHdrImageSource imageSource = new UltraHdrImageSource();
            imageSource.load(sourceFile.toUri().toURL());

            Tiler tiler = new Tiler(32, ImageInfo.IIIFVersion.V2);
            tiler.createImages(imageSource, List.of(sourceFile), tempDir.resolve("iiif"),
                               "http://localhost:8887/iiif/", 1, sink);

            int validated = 0;
            try (Stream<Path> tiles = Files.walk(tempDir.resolve("iiif"))) {
                for (Path tile : (Iterable<Path>) tiles.filter(p -> p.toString().endsWith(".jpg"))::iterator) {
                    byte[] bytes = Files.readAllBytes(tile);
                    GainMapCodec.UhdrSplit split = codec.decode(bytes);
                    assertThat(split.gainmapJpeg()).isNotEmpty();
                    assertThat(split.metadataJson()).contains("alternateHdrHeadroom");
                    validated++;
                }
            }
            assertThat(validated).isGreaterThanOrEqualTo(4);
        } finally {
            // The shared codec stays open until tearDown.
        }
    }

    private UltraHdrTileSink testSink() {
        DefaultTileSink delegate = new DefaultTileSink();
        delegate.setOptions(Map.of("format", "jpg"));
        return new UltraHdrTileSink(codec, delegate);
    }
}
