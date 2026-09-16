// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/test/java/de/christianmahnke/iiif/fliiifenleger/ultrahdr/UltraHdrTileSinkTest.java
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.Tiler;
import de.christianmahnke.iiif.fliiifenleger.sink.DefaultTileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import de.christianmahnke.iiif.fliiifenleger.source.DefaultImageSource;
import de.christianmahnke.iiif.fliiifenleger.source.HdrFrame;
import de.christianmahnke.iiif.fliiifenleger.source.HdrSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
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
    @DisplayName("tests run headless")
    void runsHeadless() {
        assertThat(GraphicsEnvironment.isHeadless()).isTrue();
    }

    @Test
    @DisplayName("the ultrahdr source splits primary and gain map")
    void sourceSplitsGainMap(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("source.jpg");
        Files.write(file, uhdrSource);

        ImageSource source = ultraHdrSource(file);
        BufferedImage primary = source.getImage();
        assertThat(primary.getWidth()).isEqualTo(64);
        assertThat(primary.getHeight()).isEqualTo(64);

        HdrFrame frame = ((HdrSource) source).getHdrFrame();
        assertThat(frame).isNotNull();
        assertThat(frame.gainmap()).isNotNull();
        assertThat(frame.gainmap().width()).isEqualTo(32);
        assertThat(frame.gainmap().height()).isEqualTo(32);
        assertThat(frame.gainmap().metadataJson()).contains("alternateHdrHeadroom");
    }

    @Test
    @DisplayName("a plain JPEG source degrades gracefully (no gain map)")
    void plainJpegDegrades(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("plain.jpg");
        Files.write(file, jpeg(32, 32, 0));

        ImageSource source = ultraHdrSource(file);
        assertThat(source.getImage()).isNotNull();
        assertThat(((HdrSource) source).getHdrFrame()).isNull();
    }

    // ── UltraHdrTileSink ──────────────────────────────────────────────────────

    /** 64×64 primary with a 32×32 gain map (2:1 ratio). */
    private static HdrFrame testFrame() {
        float[] primary = new float[64 * 64 * 3];
        java.util.Arrays.fill(primary, 0.5f);
        float[] gain = new float[32 * 32 * 3];
        java.util.Arrays.fill(gain, 1.0f);
        HdrFrame.GainMap gainMap = new HdrFrame.GainMap(32, 32, 3, gain, METADATA_JSON);
        return new HdrFrame(64, 64, 3, primary,
            HdrFrame.TransferFunction.SRGB, HdrFrame.Primaries.BT709, gainMap);
    }

    /** Metadata as the Tiler hands it over: HDR frame plus region keys. */
    private Map<String, Object> frameMetadata(HdrFrame frame,
                                              int x, int y, int w, int h, int scale) {
        Map<String, Object> metadata = tileMetadata(x, y, w, h, scale);
        metadata.put(HdrFrame.META_FRAME, frame);
        return metadata;
    }

    @Test
    @DisplayName("saveTile assembles an UltraHDR tile with the gain map")
    void saveTileCarriesGainMap(@TempDir Path tempDir) throws Exception {
        try (UltraHdrTileSink sink = testSink()) {
            // 64×64 primary tile, full region; gainmap crop 32×32.
            Map<String, Object> metadata = frameMetadata(testFrame(), 0, 0, 64, 64, 1);

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
    @DisplayName("sink opts into HDR so the Tiler attaches frames")
    void sinkSupportsHdr() {
        assertThat(new UltraHdrTileSink().supportsHdr()).isTrue();
    }

    @Test
    @DisplayName("gain map crop stays proportional at scaled levels")
    void cropStaysProportionalAtScale() {
        // 64×64 region at scale 2 renders a 32×32 primary tile: the gain
        // map crop must be 16×16 (ratio 2 preserved), not 32×32.
        HdrFrame.GainMap cropped = UltraHdrTileSink.cropGainMap(
            testFrame(), frameMetadata(testFrame(), 0, 0, 64, 64, 2));
        assertThat(cropped.width()).isEqualTo(16);
        assertThat(cropped.height()).isEqualTo(16);
    }

    @Test
    @DisplayName("gain map crop maps non-uniform subsampling per axis")
    void cropMapsNonUniformRatio() {
        float[] primary = new float[64 * 32 * 3];
        float[] gain = new float[32 * 8 * 3];
        HdrFrame.GainMap gainMap = new HdrFrame.GainMap(32, 8, 3, gain, METADATA_JSON);
        HdrFrame frame = new HdrFrame(64, 32, 3, primary,
            HdrFrame.TransferFunction.SRGB, HdrFrame.Primaries.BT709, gainMap);
        Map<String, Object> metadata = tileMetadata(0, 0, 64, 32, 1);
        metadata.put(HdrFrame.META_FRAME, frame);
        HdrFrame.GainMap cropped = UltraHdrTileSink.cropGainMap(frame, metadata);
        assertThat(cropped.width()).isEqualTo(32);
        assertThat(cropped.height()).isEqualTo(8);
    }

    @Test
    @DisplayName("missing region keys fall back to the full gain map")
    void cropFallsBackToFullFrame() {
        HdrFrame frame = testFrame();
        HdrFrame.GainMap cropped = UltraHdrTileSink.cropGainMap(
            frame, Map.of(HdrFrame.META_FRAME, frame));
        assertThat(cropped.width()).isEqualTo(32);
        assertThat(cropped.height()).isEqualTo(32);
    }

    @Test
    @DisplayName("saveTile derives a gain map for true-HDR frames")
    void saveTileDerivesGainMap(@TempDir Path tempDir) throws Exception {
        // LINEAR frame at 2× white, no gain map (JXL-HDR shape).
        float[] pixels = new float[16 * 16 * 3];
        java.util.Arrays.fill(pixels, 2.0f);
        HdrFrame frame = new HdrFrame(16, 16, 3, pixels,
            HdrFrame.TransferFunction.LINEAR, HdrFrame.Primaries.BT2020);
        try (UltraHdrTileSink sink = testSink()) {
            Map<String, Object> metadata = frameMetadata(frame, 0, 0, 16, 16, 1);
            Path out = tempDir.resolve("tile.jpg");
            try (OutputStream os = Files.newOutputStream(out)) {
                sink.saveTile(os, ImageIO.read(new java.io.ByteArrayInputStream(jpeg(16, 16, 0))), metadata);
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

    @Test
    @DisplayName("setOptions rejects invalid threads values")
    void setOptionsInvalidThreadsThrows() {
        for (String bad : new String[]{"0", "-1", "many"}) {
            UltraHdrTileSink sink = new UltraHdrTileSink();
            assertThatThrownBy(() -> sink.setOptions(Map.of("threads", bad)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threads");
        }
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

    @Test
    @DisplayName("Tiler end-to-end with threads=4: every tile carries a gain map")
    void tilerEndToEndParallelCarriesGainMaps(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("source.jpg");
        Files.write(sourceFile, uhdrSource);

        UltraHdrTileSink sink = new UltraHdrTileSink();
        sink.setOptions(Map.of("format", "jpg", "threads", "4"));
        try {
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
            sink.close();
        }
    }

    private UltraHdrTileSink testSink() {
        DefaultTileSink delegate = new DefaultTileSink();
        delegate.setOptions(Map.of("format", "jpg"));
        return new UltraHdrTileSink(codec, delegate);
    }
}
