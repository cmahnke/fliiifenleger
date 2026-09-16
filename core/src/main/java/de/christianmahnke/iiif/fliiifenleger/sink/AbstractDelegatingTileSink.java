// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

package de.christianmahnke.iiif.fliiifenleger.sink;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.Tiler;

import java.util.Map;

/**
 * Shared base for {@link TileSink} decorators that wrap a delegate sink.
 *
 * <p>The C2PA ({@code c2pa}) and UltraHDR ({@code ultrahdr}) sinks both
 * render each tile through a delegate sink and then post-process it (signing
 * or gain-map assembly).  They share the delegate plumbing: the
 * {@code delegate} / {@code runtime} / {@code threads} options, lazy delegate
 * resolution through {@link Tiler#SINK_REGISTRY}, delegate info.json
 * extension merging and delegate lifecycle.  That machinery lives here;
 * subclasses only add their codec-specific work (and the WASM lane
 * parallelism, which depends on the {@code wasm-runtime} module that core
 * does not reference).
 */
public abstract class AbstractDelegatingTileSink extends AbstractTileSink {

    protected String delegateName = "default";
    /** WASM engine selection string ({@code auto}/{@code chicory}/{@code graalvm}) or {@code null}. */
    protected String engine       = null;
    /** Parallel lanes; {@code 0} falls back to {@code -Dwasm.lanes}. */
    protected int    threads      = 0;

    /** Delegate sink; resolved lazily so tests can inject one. */
    protected TileSink delegate;

    /** Whether this sink created (and must close) the delegate. */
    protected boolean ownsDelegate = false;

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * Parses the {@code delegate}, {@code runtime} and {@code threads} options
     * shared by every delegating sink.  Call from a subclass {@code setOptions}
     * after {@code super.setOptions(options)}.
     *
     * @param options  The raw options map (may be {@code null}).
     * @param sinkName Sink name used in error messages (e.g. {@code "C2paTileSink"}).
     */
    protected void parseDelegateOptions(Map<String, String> options, String sinkName) {
        if (options == null) {
            return;
        }
        if (options.containsKey("delegate")) {
            this.delegateName = options.get("delegate");
        }
        if (options.containsKey("runtime")) {
            this.engine = options.get("runtime");
        }
        if (options.containsKey("threads")) {
            try {
                int threads = Integer.parseInt(options.get("threads").trim());
                if (threads < 1) {
                    throw new IllegalArgumentException(
                        sinkName + " option 'threads' must be at least 1, got '" + options.get("threads") + "'");
                }
                this.threads = threads;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    sinkName + " option 'threads' must be a positive integer, got '"
                    + options.get("threads") + "'", e);
            }
        }
    }

    // ── Delegate resolution ───────────────────────────────────────────────────

    /**
     * Returns the delegate sink, resolving it from {@link Tiler#SINK_REGISTRY}
     * on first use (lazy so tests can inject one).
     */
    protected TileSink delegate() {
        if (delegate == null) {
            synchronized (this) {
                if (delegate == null) {
                    delegate = createDelegate();
                }
            }
        }
        return delegate;
    }

    private TileSink createDelegate() {
        TileSink template = Tiler.SINK_REGISTRY.get(delegateName);
        if (template == null) {
            throw new IllegalArgumentException(
                "Unknown delegate sink: '" + delegateName + "'");
        }
        try {
            TileSink created = template.getClass().getConstructor().newInstance();
            // Propagate the format option to the delegate.
            created.setOptions(Map.of("format", format));
            ownsDelegate = true;
            return created;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Cannot instantiate delegate sink '" + delegateName + "'", e);
        }
    }

    /**
     * Returns the delegate sink's extension so stacked sinks (e.g. C2PA over
     * UltraHDR) advertise both capabilities. Empty when the delegate is the
     * plain default sink.
     */
    protected InfoExtension delegateExtension(ImageInfo.IIIFVersion version) {
        try {
            if (delegate != null) {
                if (delegate.getName().equals(getName())) {
                    return InfoExtension.empty();
                }
                return delegate.getInfoJsonExtension(version);
            }
            if (delegateName == null || delegateName.equals("default") || delegateName.equals(getName())) {
                return InfoExtension.empty();
            }
            TileSink template = Tiler.SINK_REGISTRY.get(delegateName);
            if (template == null) {
                return InfoExtension.empty();
            }
            TileSink instance = template.getClass().getConstructor().newInstance();
            return instance.getInfoJsonExtension(version);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                .debug("Cannot resolve delegate info.json extension: {}", e.getMessage());
            return InfoExtension.empty();
        }
    }

    /**
     * Closes the delegate if this sink created it.  Injected (shared)
     * instances are left alone.
     */
    protected void closeDelegate() {
        if (delegate instanceof AutoCloseable closeable && ownsDelegate) {
            try {
                closeable.close();
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                    .debug("Failed to close delegate sink: {}", e.getMessage());
            }
        }
    }
}
