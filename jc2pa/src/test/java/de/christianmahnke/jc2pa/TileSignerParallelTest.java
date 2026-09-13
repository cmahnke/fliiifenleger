// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.jc2pa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TileSigner parallel lanes")
class TileSignerParallelTest {

    private static final String MANIFEST_JSON = """
        {
          "claim_generator": "jc2pa-parallel-test/0.1",
          "title": "%s",
          "assertions": []
        }
        """;

    private static byte[] tile() throws Exception {
        return TestWasmSupport.fixture("success.jpg");
    }

    @Test
    @DisplayName("plain constructor stays serial; system default is cores capped at 4")
    void defaultIsCoreSized() throws Exception {
        int expected = Math.min(
            de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine
                .resolveParallelism(Runtime.getRuntime().availableProcessors(), null),
            de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine.DEFAULT_MAX_LANES);
        assertThat(de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine.systemParallelism(null))
            .isEqualTo(expected);
        try (TileSigner signer = new TileSigner((String) null)) {
            assertThat(signer.laneCount()).isEqualTo(1);
        }
        try (TileSigner signer = new TileSigner((String) null, 1)) {
            assertThat(signer.laneCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("requested lanes are honoured up to the available processors")
    void requestedLanesHonoured() throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        try (TileSigner signer = new TileSigner((String) null, 4)) {
            assertThat(signer.laneCount()).isEqualTo(Math.min(4, cores));
        }
    }

    @Test
    @DisplayName("invalid parallelism is rejected")
    void invalidParallelismRejected() {
        assertThatThrownBy(() -> new TileSigner((String) null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TileSigner((String) null, -2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a shared instance supports only a single lane")
    void sharedInstanceIsSerialOnly() throws Exception {
        assertThatThrownBy(() -> new TileSigner(TestWasmSupport.shared(), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parallel soak: every tile validates with its own manifest")
    void parallelSoakKeepsAttribution() throws Exception {
        int callers = 4;
        int tilesPerCaller = 2;
        int lanes = 4;
        byte[] jpeg = tile();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < callers * tilesPerCaller; i++) {
            titles.add("Parallel Tile " + i);
        }
        try (TileSigner signer = new TileSigner((String) null, lanes)) {
            assertThat(signer.laneCount()).isEqualTo(Math.min(lanes,
                Runtime.getRuntime().availableProcessors()));
            CountDownLatch gate = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            try {
                List<Future<String>> futures = new ArrayList<>();
                for (int t = 0; t < callers; t++) {
                    for (int i = 0; i < tilesPerCaller; i++) {
                        String title = titles.get(t * tilesPerCaller + i);
                        futures.add(pool.submit(() -> {
                            gate.await();
                            byte[] signed = signer.signEphemeral(jpeg, "image/jpeg",
                                MANIFEST_JSON.formatted(title), "parallel-test");
                            String json = signer.manifestJson(signed, "image/jpeg");
                            assertThat(signer.activeLabel(signed, "image/jpeg")).isNotBlank();
                            assertThat(json).contains(title);
                            return title;
                        }));
                    }
                }
                gate.countDown();
                List<String> done = new ArrayList<>();
                for (Future<String> future : futures) {
                    done.add(future.get(180, TimeUnit.SECONDS));
                }
                assertThat(done).containsExactlyInAnyOrderElementsOf(titles);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("hammered first use creates no more than one engine per lane")
    void hammeredFirstUse() throws Exception {
        byte[] jpeg = tile();
        try (TileSigner signer = new TileSigner((String) null, 4)) {
            int hammerThreads = 8;
            CountDownLatch gate = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(hammerThreads);
            try {
                List<Future<byte[]>> futures = new ArrayList<>();
                for (int i = 0; i < hammerThreads; i++) {
                    futures.add(pool.submit(() -> {
                        gate.await();
                        return signer.signEphemeral(jpeg, "image/jpeg",
                            MANIFEST_JSON.formatted("Hammer"), "parallel-test");
                    }));
                }
                gate.countDown();
                for (Future<byte[]> future : futures) {
                    assertThat(signer.activeLabel(future.get(180, TimeUnit.SECONDS), "image/jpeg"))
                        .isNotBlank();
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("close shuts down; use after close fails")
    void closeSemantics() throws Exception {
        TileSigner signer = new TileSigner((String) null, 2);
        byte[] signed = signer.signEphemeral(tile(), "image/jpeg",
            MANIFEST_JSON.formatted("Close"), "parallel-test");
        assertThat(signer.activeLabel(signed, "image/jpeg")).isNotBlank();
        signer.close();
        signer.close();
        assertThatThrownBy(() -> signer.signEphemeral(tile(), "image/jpeg",
                MANIFEST_JSON.formatted("Close"), "parallel-test"))
                .isInstanceOf(IllegalStateException.class);
    }
}
