// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.wasm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WasmLanePool")
class WasmLanePoolTest {

    /** Fake module instance: tracks creation and close calls by identity. */
    static class FakeWasm implements AutoCloseable {
        static final AtomicInteger created = new AtomicInteger();
        static final AtomicInteger closed = new AtomicInteger();

        FakeWasm() {
            created.incrementAndGet();
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }

        static void reset() {
            created.set(0);
            closed.set(0);
        }
    }

    @BeforeEach
    void resetCounters() {
        FakeWasm.reset();
    }

    @AfterEach
    void restoreLanesProperty() {
        System.clearProperty(WasmEngine.LANES_PROPERTY);
    }

    record Call(Thread thread, FakeWasm instance) {
    }

    @Test
    @DisplayName("constructor rejects parallelism below 1")
    void rejectsInvalidParallelism() {
        assertThatThrownBy(() -> new WasmLanePool<>("test", 0, FakeWasm::new, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WasmLanePool<>("test", -2, FakeWasm::new, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("laneCount reports the configured size")
    void laneCountMatches() {
        try (WasmLanePool<FakeWasm> pool = new WasmLanePool<>("test", 3, FakeWasm::new, true)) {
            assertThat(pool.laneCount()).isEqualTo(3);
        }
        try (WasmLanePool<FakeWasm> pool = new WasmLanePool<>("test", 1, FakeWasm::new, true)) {
            assertThat(pool.laneCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("tasks spread round-robin with strict thread-instance affinity")
    void roundRobinWithAffinity() throws Exception {
        int lanes = 3;
        int tasks = 9;
        List<Call> calls;
        try (WasmLanePool<FakeWasm> pool = new WasmLanePool<>("test", lanes, FakeWasm::new, true)) {
            // Gate all tasks so they overlap in time.
            CountDownLatch gate = new CountDownLatch(1);
            ExecutorService callers = Executors.newFixedThreadPool(tasks);
            try {
                List<Future<Call>> futures = new ArrayList<>();
                for (int i = 0; i < tasks; i++) {
                    futures.add(callers.submit(() ->
                        pool.submit(instance -> {
                            try {
                                gate.await();
                                Thread.sleep(20);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                            return new Call(Thread.currentThread(), instance);
                        }).get(60, TimeUnit.SECONDS)));
                }
                gate.countDown();
                calls = new ArrayList<>();
                for (Future<Call> future : futures) {
                    calls.add(future.get(60, TimeUnit.SECONDS));
                }
            } finally {
                callers.shutdownNow();
            }
        }
        // Exactly one instance per lane was created.
        assertThat(FakeWasm.created.get()).isEqualTo(lanes);
        // Each worker thread always saw the same instance and vice versa.
        Map<Thread, FakeWasm> byThread = new HashMap<>();
        Map<FakeWasm, Thread> byInstance = new HashMap<>();
        for (Call call : calls) {
            assertThat(byThread.putIfAbsent(call.thread(), call.instance()))
                    .as("thread must keep its instance")
                    .isIn(null, call.instance());
            assertThat(byInstance.putIfAbsent(call.instance(), call.thread()))
                    .as("instance must keep its thread")
                    .isIn(null, call.thread());
        }
        assertThat(byThread).hasSize(lanes);
    }

    @Test
    @DisplayName("close shuts down owned instances once; submit after close fails")
    void closeSemantics() throws Exception {
        WasmLanePool<FakeWasm> pool = new WasmLanePool<>("test", 2, FakeWasm::new, true);
        pool.submit(instance -> instance).get(30, TimeUnit.SECONDS);
        pool.submit(instance -> instance).get(30, TimeUnit.SECONDS);
        assertThat(FakeWasm.created.get()).isEqualTo(2);
        pool.close();
        pool.close();
        assertThat(FakeWasm.closed.get()).isEqualTo(2);
        assertThatThrownBy(() -> pool.submit(instance -> instance))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("unowned instances are not closed")
    void unownedInstancesSurviveClose() throws Exception {
        FakeWasm shared = new FakeWasm();
        FakeWasm.reset();
        WasmLanePool<FakeWasm> pool = new WasmLanePool<>("test", 1, () -> shared, false);
        pool.submit(instance -> instance).get(30, TimeUnit.SECONDS);
        pool.close();
        assertThat(FakeWasm.closed.get()).isZero();
    }

    @Test
    @DisplayName("initEagerly creates the first lane now and surfaces load failures")
    void initEagerly() throws Exception {
        try (WasmLanePool<FakeWasm> pool = new WasmLanePool<>("test", 3, FakeWasm::new, true)) {
            assertThat(FakeWasm.created.get()).isZero();
            pool.initEagerly();
            assertThat(FakeWasm.created.get()).isEqualTo(1);
        }
        WasmLanePool<FakeWasm> failing =
                new WasmLanePool<>("test", 2, () -> { throw new IOException("no module"); }, true);
        assertThatThrownBy(failing::initEagerly)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no module");
        failing.close();
    }

    // ── WasmEngine.resolveParallelism ─────────────────────────────────────────

    @Test
    @DisplayName("resolveParallelism validates and clamps")
    void resolveParallelismRules() {
        int cores = Runtime.getRuntime().availableProcessors();
        assertThatThrownBy(() -> WasmEngine.resolveParallelism(0, "chicory"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WasmEngine.resolveParallelism(-1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(WasmEngine.resolveParallelism(1, "chicory")).isEqualTo(1);
        assertThat(WasmEngine.resolveParallelism(1_000_000, "chicory")).isEqualTo(cores);
        assertThat(WasmEngine.resolveParallelism(1_000_000, "graalvm"))
                .isEqualTo(Math.min(cores, WasmEngine.MAX_GRAAL_LANES));
        assertThat(WasmEngine.resolveParallelism(1, "graalvm")).isEqualTo(1);
    }

    @Test
    @DisplayName("systemParallelism defaults to cores capped at 4 and validates the property")
    void systemParallelismRules() {
        int cores = Runtime.getRuntime().availableProcessors();
        int capped = Math.min(cores, WasmEngine.DEFAULT_MAX_LANES);
        try {
            System.clearProperty(WasmEngine.LANES_PROPERTY);
            assertThat(WasmEngine.systemParallelism("chicory")).isEqualTo(capped);
            System.setProperty(WasmEngine.LANES_PROPERTY, "1");
            assertThat(WasmEngine.systemParallelism("chicory")).isEqualTo(1);
            System.setProperty(WasmEngine.LANES_PROPERTY, "3");
            assertThat(WasmEngine.systemParallelism("chicory")).isEqualTo(Math.min(3, cores));
            System.setProperty(WasmEngine.LANES_PROPERTY, "bogus");
            assertThatThrownBy(() -> WasmEngine.systemParallelism("chicory"))
                    .isInstanceOf(IllegalArgumentException.class);
            System.setProperty(WasmEngine.LANES_PROPERTY, "0");
            assertThatThrownBy(() -> WasmEngine.systemParallelism("chicory"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            System.clearProperty(WasmEngine.LANES_PROPERTY);
        }
    }
}
