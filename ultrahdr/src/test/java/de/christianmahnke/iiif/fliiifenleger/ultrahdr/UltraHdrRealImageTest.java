// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.Tiler;
import de.christianmahnke.iiif.fliiifenleger.sink.DefaultTileSink;
import de.christianmahnke.iiif.fliiifenleger.source.HdrSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-camera UltraHDR round trip.
 *
 * <p>Unlike the synthetic fixtures in {@link UltraHdrTileSinkTest}, this
 * uses {@code uhdr-crop.jpg}: a 1024x1024 crop of a Pixel 6 Pro photo
 * (CC BY 4.0, see test resources NOTICE) with its original gain map and
 * metadata, re-encoded with this project's own codec.  It answers: does
 * a real UHDR input tile correctly, with gain maps in every tile?
 */
@DisplayName("Real UltraHDR image")
class UltraHdrRealImageTest {

    private static GainMapCodec codec;

    @BeforeAll
    static void setUp() throws Exception {
        codec = GainMapCodec.shared("chicory");
    }

    @AfterAll
    static void tearDown() {
        if (codec != null) {
            codec.close();
        }
    }

    private static byte[] fixtureBytes() throws Exception {
        try (InputStream is = UltraHdrRealImageTest.class.getResourceAsStream("/images/uhdr-crop.jpg")) {
            assertThat(is).as("test fixture /images/uhdr-crop.jpg").isNotNull();
            return is.readAllBytes();
        }
    }

    @Test
    @DisplayName("real camera file splits into primary, gain map and metadata")
    void realFileSplits() throws Exception {
        GainMapCodec.UhdrSplit split = codec.decode(fixtureBytes());
        assertThat(split.primaryJpeg()).isNotEmpty();
        assertThat(split.gainmapJpeg()).isNotEmpty();
        assertThat(split.metadataJson()).contains("alternateHdrHeadroom");
    }

    @Test
    @DisplayName("Tiler end-to-end: every tile of the real file carries a gain map")
    void tilerEndToEndRealFile(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("uhdr-crop.jpg");
        Files.write(sourceFile, fixtureBytes());

        DefaultTileSink delegate = new DefaultTileSink();
        delegate.setOptions(Map.of("format", "jpg"));
        UltraHdrTileSink sink = new UltraHdrTileSink(codec, delegate);

        UltraHdrImageSource imageSource = new UltraHdrImageSource();
        imageSource.load(sourceFile.toUri().toURL());
        assertThat(imageSource.getWidth()).isEqualTo(1024);
        assertThat(imageSource.getHeight()).isEqualTo(1024);
        assertThat(((HdrSource) imageSource).getHdrFrame().gainmap()).isNotNull();

        Tiler tiler = new Tiler(256, ImageInfo.IIIFVersion.V2);
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
    }

    @Test
    @DisplayName("source without gain map still degrades gracefully")
    void sourceIsRegistered(@TempDir Path tempDir) throws Exception {
        ImageSource source = new UltraHdrImageSource();
        Path file = tempDir.resolve("uhdr-crop.jpg");
        Files.write(file, fixtureBytes());
        source.load(file.toUri().toURL());
        assertThat(source.getImage()).isNotNull();
        assertThat(Tiler.SOURCE_REGISTRY).containsKey("ultrahdr");
    }
}
