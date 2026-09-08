// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/test/java/de/christianmahnke/iiif/fliiifenleger/ultrahdr/GainMapCodecTest.java
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link GainMapCodec}.
 *
 * <p>The synthetic UltraHDR files are produced by the codec itself
 * (encode → decode round-trip), so the tests are self-contained.
 *
 * <p>All WASM access goes through the shared {@link GainMapCodec}'s
 * dedicated thread.
 */
@DisplayName("GainMapCodec")
class GainMapCodecTest {

    /** ISO 21496-1 metadata, camelCase — the format the codec exchanges. */
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

    private static GainMapCodec codec;

    @BeforeAll
    static void setUp() throws IOException {
        // One process-wide instance only — see GainMapCodec documentation.
        codec = GainMapCodec.shared("chicory");
    }

    @AfterAll
    static void tearDown() {
        if (codec != null) {
            codec.close();
        }
    }

    /** A synthetic primary image JPEG. */
    private static byte[] jpeg(int w, int h, Color fill) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(fill);
            g.fillRect(0, 0, w, h);
            g.setColor(fill.darker());
            g.fillRect(w / 4, h / 4, w / 2, h / 2);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    // ── Version ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("version reports the pinned ultrahdr-rs version")
    void version() {
        assertThat(codec.version()).matches("\\d+\\.\\d+.*");
    }

    // ── Encode / decode round-trip ────────────────────────────────────────────

    @Test
    @DisplayName("encode → decode round-trip preserves metadata and produces valid parts")
    void roundTrip() throws Exception {
        byte[] primary = jpeg(64, 64, Color.RED);
        byte[] gainmap = jpeg(16, 16, Color.BLUE);

        byte[] uhdr = codec.encode(primary, gainmap, METADATA_JSON, 90, 85);
        assertThat(uhdr).isNotEmpty().hasSizeGreaterThan(primary.length);

        GainMapCodec.UhdrSplit split = codec.decode(uhdr);
        assertThat(split.primaryJpeg()).isNotEmpty();
        assertThat(split.gainmapJpeg()).isNotEmpty();
        assertThat(split.metadataJson()).isNotBlank();

        // The metadata round-trips (values preserved).
        assertThat(split.metadataJson()).contains("2.0");
        assertThat(split.metadataJson()).contains("alternateHdrHeadroom");

        // Both parts decode as images.
        BufferedImage decodedPrimary = ImageIO.read(new java.io.ByteArrayInputStream(split.primaryJpeg()));
        BufferedImage decodedGainmap = ImageIO.read(new java.io.ByteArrayInputStream(split.gainmapJpeg()));
        assertThat(decodedPrimary).isNotNull();
        assertThat(decodedGainmap).isNotNull();
        // The codec re-encodes at its own quality — dimensions are preserved.
        assertThat(decodedPrimary.getWidth()).isEqualTo(64);
        assertThat(decodedPrimary.getHeight()).isEqualTo(64);
        assertThat(decodedGainmap.getWidth()).isEqualTo(16);
        assertThat(decodedGainmap.getHeight()).isEqualTo(16);
    }

    // ── Degradation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("decode of a plain JPEG fails with an UltraHdrException")
    void decodePlainJpegFails() throws Exception {
        byte[] plain = jpeg(32, 32, Color.GREEN);
        assertThatThrownBy(() -> codec.decode(plain))
            .isInstanceOf(UltraHdrException.class);
    }

    @Test
    @DisplayName("encode with invalid metadata JSON fails with an UltraHdrException")
    void encodeInvalidMetadataFails() throws Exception {
        byte[] primary = jpeg(32, 32, Color.MAGENTA);
        assertThatThrownBy(() -> codec.encode(primary, primary, "not json", 90, 85))
            .isInstanceOf(UltraHdrException.class);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("close on the shared codec is a no-op (JVM-lifetime instance)")
    void sharedCloseIsNoOp() throws Exception {
        codec.close();
        // The shared codec must stay usable afterwards.
        assertThat(codec.version()).isNotBlank();
    }

    @Test
    @DisplayName("concurrent encode calls are serialized and all succeed")
    void concurrentEncoding() throws Exception {
        byte[] primary = jpeg(48, 48, Color.CYAN);
        byte[] gainmap = jpeg(12, 12, Color.ORANGE);

        int threads = 4;
        var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var latch = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<byte[]>>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    latch.await();
                    return codec.encode(primary, gainmap, METADATA_JSON, 90, 85);
                }));
            }
            latch.countDown();
            int ok = 0;
            for (var f : futures) {
                assertThat(f.get()).isNotEmpty();
                ok++;
            }
            assertThat(ok).isEqualTo(threads);
        } finally {
            executor.shutdown();
        }
    }
}
