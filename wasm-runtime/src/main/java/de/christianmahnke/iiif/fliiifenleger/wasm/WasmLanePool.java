// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.wasm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A fixed pool of WASM lanes for parallel execution.
 *
 * <p>A lane is one interpreter instance bound to one dedicated worker thread.
 * WASM instances are neither thread-safe nor allowed to hop host threads, so
 * each lane's instance is created lazily on (and used exclusively by) its own
 * thread; tasks are routed round-robin across lanes.  Separate instances are
 * fully isolated (each owns its linear memory), which is what makes parallel
 * execution sound — see the spike ({@code WasmParallelSpikeTest} history).
 *
 * <p>With a single lane this behaves exactly like the previous dedicated
 * single-thread executors.
 *
 * @param <W> The WASM module wrapper type (e.g. per-codec binding class).
 */
public final class WasmLanePool<W extends AutoCloseable> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WasmLanePool.class);

    /** Creates one lane instance; may throw when the module cannot load. */
    @FunctionalInterface
    public interface LaneFactory<W> {
        W create() throws IOException;
    }

    /** One lane: a single worker thread plus its lazily created instance. */
    private final class Lane {
        final ExecutorService executor;
        volatile W instance;

        Lane(String threadName) {
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, threadName);
                t.setDaemon(true);
                return t;
            });
        }

        <T> Future<T> submit(Function<W, T> task) {
            return executor.submit(() -> {
                W inst = instance;
                if (inst == null) {
                    inst = factory.create();
                    instance = inst;
                    created.add(inst);
                }
                return task.apply(inst);
            });
        }

        void shutdown() {
            executor.shutdown();
        }
    }

    private final LaneFactory<W> factory;
    private final List<Lane> lanes;
    private final Set<W> created = ConcurrentHashMap.newKeySet();
    private final AtomicInteger cursor = new AtomicInteger();
    private final boolean ownsInstances;
    private volatile boolean closed = false;

    /**
     * @param threadNamePrefix Worker thread base name ({@code "<prefix>-<i>"},
     *                         or exactly {@code prefix} for a single lane).
     * @param parallelism      Number of lanes; must be at least 1.
     * @param factory          Creates one module instance per lane.
     * @param ownsInstances    Whether {@link #close()} closes created instances.
     * @throws IllegalArgumentException if {@code parallelism < 1}.
     */
    public WasmLanePool(String threadNamePrefix, int parallelism,
                        LaneFactory<W> factory, boolean ownsInstances) {
        if (parallelism < 1) {
            throw new IllegalArgumentException(
                "WASM lane parallelism must be at least 1, got " + parallelism);
        }
        this.factory = factory;
        this.ownsInstances = ownsInstances;
        List<Lane> built = new ArrayList<>(parallelism);
        for (int i = 0; i < parallelism; i++) {
            String name = parallelism == 1 ? threadNamePrefix : threadNamePrefix + "-" + i;
            built.add(new Lane(name));
        }
        this.lanes = Collections.unmodifiableList(built);
    }

    /** @return The number of lanes. */
    public int laneCount() {
        return lanes.size();
    }

    /** @return The number of lane instances created so far (for tests). */
    int createdCount() {
        return created.size();
    }

    /**
     * Runs {@code task} on the next lane's thread with that lane's instance,
     * creating the instance on first use.
     *
     * @throws IllegalStateException if the pool has been closed.
     */
    public <T> Future<T> submit(Function<W, T> task) {
        if (closed) {
            throw new IllegalStateException("WASM lane pool has been closed");
        }
        int index = Math.floorMod(cursor.getAndIncrement(), lanes.size());
        return lanes.get(index).submit(task);
    }

    /**
     * Creates the first lane's instance now (on its own thread), surfacing
     * load failures early instead of on first use.
     *
     * @throws IOException if the module cannot be loaded.
     */
    public void initEagerly() throws IOException {
        try {
            submit(instance -> null).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("WASM lane initialisation was interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioCause) {
                throw ioCause;
            }
            if (cause instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            throw new IOException("WASM lane initialisation failed", cause);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Lane lane : lanes) {
            lane.shutdown();
        }
        if (ownsInstances) {
            for (W instance : created) {
                try {
                    instance.close();
                } catch (Exception e) {
                    // Narrowed close must not mask earlier outcomes.
                    log.debug("Error closing WASM lane instance: {}", e.getMessage());
                }
            }
        }
        created.clear();
    }
}
