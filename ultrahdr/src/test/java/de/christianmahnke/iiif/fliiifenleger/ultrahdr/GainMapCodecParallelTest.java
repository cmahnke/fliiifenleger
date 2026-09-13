// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GainMapCodec parallel lanes")
class GainMapCodecParallelTest {

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

    private static byte[] jpeg(int w, int h, int variant) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(30 * variant, 100, 200));
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("plain constructor stays serial; system default is cores capped at 4")
    void defaultIsCoreSized() throws Exception {
        int expected = Math.min(
            de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine
                .resolveParallelism(Runtime.getRuntime().availableProcessors(), "chicory"),
            de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine.DEFAULT_MAX_LANES);
        assertThat(de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine.systemParallelism("chicory"))
            .isEqualTo(expected);
        try (GainMapCodec codec = new GainMapCodec("chicory")) {
            assertThat(codec.laneCount()).isEqualTo(1);
        }
        try (GainMapCodec codec = new GainMapCodec("chicory", 1)) {
            assertThat(codec.laneCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("requested lanes are honoured up to the available processors")
    void requestedLanesHonoured() throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        try (GainMapCodec codec = new GainMapCodec("chicory", 4)) {
            assertThat(codec.laneCount()).isEqualTo(Math.min(4, cores));
        }
    }

    @Test
    @DisplayName("invalid parallelism is rejected")
    void invalidParallelismRejected() {
        assertThatThrownBy(() -> new GainMapCodec("chicory", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GainMapCodec("chicory", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parallel soak: every assembled tile decodes with its own gain map")
    void parallelSoakKeepsAttribution() throws Exception {
        int callers = 4;
        int tilesPerCaller = 2;
        // Distinct flat gain-map colors per tile prove attribution: the
        // decoded gain map's mean red channel must match the tile's own
        // variant (survives resampling and lossy re-encode).
        try (GainMapCodec codec = new GainMapCodec("chicory", 4)) {
            CountDownLatch gate = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            try {
                List<Future<Integer>> futures = new ArrayList<>();
                for (int t = 0; t < callers; t++) {
                    for (int i = 0; i < tilesPerCaller; i++) {
                        int variant = t * tilesPerCaller + i + 1;
                        futures.add(pool.submit(() -> {
                            gate.await();
                            byte[] assembled = codec.encode(
                                jpeg(64, 64, 0), jpeg(32, 32, variant),
                                METADATA_JSON, 90, 85);
                            GainMapCodec.UhdrSplit split = codec.decode(assembled);
                            assertThat(split.gainmapJpeg()).isNotEmpty();
                            assertThat(split.metadataJson()).contains("alternateHdrHeadroom");
                            BufferedImage gainmap =
                                ImageIO.read(new java.io.ByteArrayInputStream(split.gainmapJpeg()));
                            assertThat(gainmap).isNotNull();
                            return meanRed(gainmap);
                        }));
                    }
                }
                gate.countDown();
                List<Integer> means = new ArrayList<>();
                for (Future<Integer> future : futures) {
                    means.add(future.get(180, TimeUnit.SECONDS));
                }
                assertThat(means).hasSize(callers * tilesPerCaller);
                for (int v = 1; v <= callers * tilesPerCaller; v++) {
                    int expected = 30 * v;
                    int actual = means.get(v - 1);
                    int closest = means.stream()
                        .mapToInt(m -> Math.abs(m - expected))
                        .min().orElseThrow();
                    assertThat(Math.abs(actual - expected))
                        .as("tile %d mean red closest to its own variant", v)
                        .isEqualTo(closest);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static int meanRed(BufferedImage image) {
        long sum = 0;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                sum += (image.getRGB(x, y) >> 16) & 0xFF;
                count++;
            }
        }
        return (int) (sum / Math.max(1, count));
    }

    @Test
    @DisplayName("close shuts down; use after close fails")
    void closeSemantics() throws Exception {
        GainMapCodec codec = new GainMapCodec("chicory", 2);
        byte[] assembled = codec.encode(jpeg(32, 32, 0), jpeg(16, 16, 1), METADATA_JSON, 90, 85);
        assertThat(codec.decode(assembled).gainmapJpeg()).isNotEmpty();
        codec.close();
        codec.close();
        assertThatThrownBy(() -> codec.encode(jpeg(32, 32, 0), jpeg(16, 16, 1), METADATA_JSON, 90, 85))
                .isInstanceOf(IllegalStateException.class);
    }
}
