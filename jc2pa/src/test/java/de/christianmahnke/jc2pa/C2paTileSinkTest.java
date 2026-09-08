// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/test/java/de/christianmahnke/jc2pa/C2paTileSinkTest.java
package de.christianmahnke.jc2pa;

import de.christianmahnke.iiif.fliiifenleger.Tiler;
import de.christianmahnke.iiif.fliiifenleger.sink.DefaultTileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSinkException;
import de.christianmahnke.iiif.fliiifenleger.source.DefaultImageSource;

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
 * Tests for {@link C2paTileSink}.
 *
 * <p>All WASM access goes through the shared {@link TileSigner} — c2pa-rs
 * keeps process-global state, so exactly one live WASM instance may exist
 * per JVM (see {@link TestWasmSupport}).
 */
@DisplayName("C2paTileSink")
class C2paTileSinkTest {

    private static final String MANIFEST_TITLE_PATTERN = "IIIF tile";

    /** A simple 64x64 test image. */
    private static BufferedImage testImage() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(0, 0, 64, 64);
            g.setColor(Color.BLUE);
            g.fillRect(16, 16, 32, 32);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Region metadata as produced by the Tiler. */
    private static Map<String, Object> regionMetadata(int x, int y, int w, int h, int scale) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("iiif.region.x", x);
        metadata.put("iiif.region.y", y);
        metadata.put("iiif.region.w", w);
        metadata.put("iiif.region.h", h);
        metadata.put("iiif.region.scale", scale);
        return metadata;
    }

    /** A sink wired to the shared signer and a default-format delegate. */
    private static C2paTileSink testSink() throws IOException {
        TileSigner signer = new TileSigner(TestWasmSupport.shared());
        DefaultTileSink delegate = new DefaultTileSink();
        delegate.setOptions(Map.of("format", "jpg"));
        return new C2paTileSink(signer, delegate);
    }

    // ── saveTile ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("saveTile writes a C2PA-signed tile with the region assertion")
    void saveTileSignsTile(@TempDir Path tempDir) throws Exception {
        try (C2paTileSink sink = testSink()) {
            Path out = tempDir.resolve("tile.jpg");
            try (OutputStream os = Files.newOutputStream(out)) {
                sink.saveTile(os, testImage(), regionMetadata(0, 0, 64, 64, 1));
            }

            byte[] tile = Files.readAllBytes(out);
            assertThat(tile.length).isGreaterThan(1000);

            // Validation must go through the signer thread.
            TileSigner signer = new TileSigner(TestWasmSupport.shared());
            try {
                assertThat(signer.activeLabel(tile, "image/jpeg")).isNotBlank();
                String json = signer.manifestJson(tile, "image/jpeg");
                assertThat(json).contains("org.projektemacher.iiif.region");
                assertThat(json).contains("\"x\": 0");
                assertThat(json).contains("\"scale\": 1");
            } finally {
                signer.close();
            }
        }
    }

    @Test
    @DisplayName("saveTile without region metadata still produces a valid manifest")
    void saveTileWithoutMetadata(@TempDir Path tempDir) throws Exception {
        try (C2paTileSink sink = testSink()) {
            Path out = tempDir.resolve("tile.jpg");
            try (OutputStream os = Files.newOutputStream(out)) {
                sink.saveTile(os, testImage(), null);
            }
            byte[] tile = Files.readAllBytes(out);

            TileSigner signer = new TileSigner(TestWasmSupport.shared());
            try {
                assertThat(signer.activeLabel(tile, "image/jpeg")).isNotBlank();
                assertThat(signer.manifestJson(tile, "image/jpeg"))
                    .contains("IIIF tile (full image)");
            } finally {
                signer.close();
            }
        }
    }

    @Test
    @DisplayName("the signed tile decodes to the same pixels")
    void signedTilePreservesPixels(@TempDir Path tempDir) throws Exception {
        BufferedImage original = testImage();

        try (C2paTileSink sink = testSink()) {
            Path out = tempDir.resolve("tile.jpg");
            try (OutputStream os = Files.newOutputStream(out)) {
                sink.saveTile(os, original, regionMetadata(0, 0, 64, 64, 1));
            }

            BufferedImage decoded = ImageIO.read(out.toFile());
            assertThat(decoded).isNotNull();
            assertThat(decoded.getWidth()).isEqualTo(64);
            assertThat(decoded.getHeight()).isEqualTo(64);
            // Spot-check pixels (JPEG is lossy — compare with tolerance):
            // red corner, blue centre.
            assertColorClose(decoded.getRGB(0, 0),  original.getRGB(0, 0));
            assertColorClose(decoded.getRGB(32, 32), original.getRGB(32, 32));
        }
    }

    // ── Options ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setOptions rejects cert without key")
    void setOptionsCertWithoutKeyThrows() {
        C2paTileSink sink = new C2paTileSink();
        assertThatThrownBy(() ->
            sink.setOptions(Map.of("cert", "/tmp/only-cert.pem")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cert");
    }

    @Test
    @DisplayName("setOptions accepts key without cert as little as cert without key")
    void setOptionsKeyWithoutCertThrows() {
        C2paTileSink sink = new C2paTileSink();
        assertThatThrownBy(() ->
            sink.setOptions(Map.of("key", "/tmp/only-key.pem")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getName returns c2pa and the sink is ServiceLoader-registered")
    void sinkIsRegistered() {
        C2paTileSink sink = new C2paTileSink();
        assertThat(sink.getName()).isEqualTo("c2pa");
        assertThat(Tiler.SINK_REGISTRY).containsKey("c2pa");
    }

    // ── End-to-end with the Tiler ─────────────────────────────────────────────

    @Test
    @DisplayName("Tiler end-to-end signs every generated tile")
    void tilerEndToEndSignsAllTiles(@TempDir Path tempDir) throws Exception {
        // Synthetic source image.
        Path sourceImage = tempDir.resolve("source.png");
        ImageIO.write(testImage(), "png", sourceImage.toFile());

        TileSigner signer = new TileSigner(TestWasmSupport.shared());
        try {
            DefaultTileSink delegate = new DefaultTileSink();
            delegate.setOptions(Map.of("format", "jpg"));
            C2paTileSink sink = new C2paTileSink(signer, delegate);

            DefaultImageSource imageSource = new DefaultImageSource();
            imageSource.load(sourceImage.toUri().toURL());

            Tiler tiler = new Tiler(32, de.christianmahnke.iiif.fliiifenleger.ImageInfo.IIIFVersion.V2);
            tiler.createImages(imageSource, List.of(sourceImage), tempDir.resolve("iiif"),
                               "http://localhost:8887/iiif/", 1, sink);

            // Walk the generated tile tree and verify every JPEG has a manifest.
            int validated = 0;
            try (Stream<Path> tiles = Files.walk(tempDir.resolve("iiif"))) {
                for (Path tile : (Iterable<Path>) tiles.filter(p -> p.toString().endsWith(".jpg"))::iterator) {
                    byte[] bytes = Files.readAllBytes(tile);
                    assertThat(bytes.length).isGreaterThan(1000);
                    assertThat(signer.activeLabel(bytes, "image/jpeg")).isNotBlank();
                    String json = signer.manifestJson(bytes, "image/jpeg");
                    assertThat(json).contains(MANIFEST_TITLE_PATTERN);
                    validated++;
                }
            }
            assertThat(validated).isGreaterThanOrEqualTo(4);
        } finally {
            signer.close();
        }
    }

    /** JPEG is lossy — per-channel comparison with a small tolerance. */
    private static void assertColorClose(int actual, int expected) {
        int tolerance = 8;
        for (int shift : new int[]{16, 8, 0}) {
            int a = (actual   >> shift) & 0xFF;
            int e = (expected >> shift) & 0xFF;
            assertThat(a).isBetween(Math.max(0, e - tolerance), Math.min(255, e + tolerance));
        }
    }
}