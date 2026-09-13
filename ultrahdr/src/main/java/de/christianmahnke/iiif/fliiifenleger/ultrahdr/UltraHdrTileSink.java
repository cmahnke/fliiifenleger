// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/iiif/fliiifenleger/ultrahdr/UltraHdrTileSink.java
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.Tiler;
import de.christianmahnke.iiif.fliiifenleger.sink.AbstractTileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSinkException;
import de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

/**
 * A {@link TileSink} decorator that integrates the UltraHDR gain map into
 * every tile: the delegate sink renders the primary tile, the correspondingly
 * cropped gain map (provided by the {@code Tiler} through the metadata map,
 * see the {@link GainMapData}
 * key constants) is re-encoded, and the ultrahdr WASM codec assembles both
 * into an UltraHDR JPEG that is written to the real output stream.
 *
 * <p>Tiles from sources without a gain map pass through unchanged (the
 * metadata entries are simply absent).
 *
 * <p><b>info.json:</b> always advertises {@code https://christianmahnke.de/iiif/hdr/}
 * (V3 service + {@code extraFeatures}, V2 {@code supports} entry).
 *
 * <p><b>Options</b> (via {@code --sink-opt}):
 * <ul>
 *   <li>{@code delegate} — name of the delegate sink (default: {@code default}).</li>
 *   <li>{@code format} — tile format (default: {@code jpg}; handled by the
 *       base class).</li>
 *   <li>{@code runtime} — WASM engine selection: {@code auto} (default),
 *       {@code chicory}, or {@code graalvm}.</li>
 *   <li>{@code quality} — JPEG quality for the primary image re-encode
 *       (default: {@code 90}).</li>
  *   <li>{@code gainmap-quality} — JPEG quality for the gain map re-encode
  *       (default: {@code 85}).</li>
  *   <li>{@code threads} — parallel assembly lanes (default: one per available
  *       processor capped at 4; {@code 1} selects serial execution). Each lane pairs one
  *       interpreter instance with its own thread;
  *       {@code -Dwasm.lanes=N} sets the default when unset.</li>
  * </ul>
 *
 * <p><b>Threading:</b> the {@code Tiler} generates tiles concurrently; all
 * WASM access is routed through the {@link GainMapCodec}'s lane pool (one lane
 * per available processor capped at 4 by default, serial with {@code threads=1}).
 */
@AutoService(TileSink.class)
public class UltraHdrTileSink extends AbstractTileSink implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UltraHdrTileSink.class);

    /** Profile URI advertising UltraHDR gain-map tiles. */
    public static final String HDR_PROFILE_URI = "https://christianmahnke.de/iiif/hdr/";
    /** JSON-LD context for the HDR extension (V3 only). */
    public static final String HDR_CONTEXT_URI = "https://christianmahnke.de/iiif/hdr/context.json";

    private String delegateName    = "default";
    private String engine          = null;  // null → WasmEngine auto selection
    private int    quality         = 90;
    private int    gainmapQuality  = 85;
    private int    threads         = 0;   // 0 → fall back to -Dwasm.lanes (one lane per core by default)

    /** Lazily created codec; one per sink instance. */
    private GainMapCodec codec;

    /** Delegate sink; resolved lazily so tests can inject one. */
    private TileSink delegate;

    /** Whether this sink created (and must close) the delegate. */
    private boolean ownsDelegate = false;

    /** Whether this sink created (and must close) the codec. */
    private boolean ownsCodec = true;

    /** Default constructor (used by ServiceLoader / reflective instantiation). */
    public UltraHdrTileSink() {
    }

    /**
     * Test constructor: uses the given codec and delegate.  The codec is
     * shared (not owned).
     *
     * @param codec    The codec to use (shared ownership).
     * @param delegate The delegate sink.
     */
    UltraHdrTileSink(GainMapCodec codec, TileSink delegate) {
        this.codec       = codec;
        this.delegate    = delegate;
        this.ownsCodec   = false;
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    @Override
    public void setOptions(Map<String, String> options) {
        super.setOptions(options);
        if (options == null) {
            return;
        }
        if (options.containsKey("delegate")) {
            this.delegateName = options.get("delegate");
        }
        if (options.containsKey("runtime")) {
            this.engine = options.get("runtime");
        }
        if (options.containsKey("quality")) {
            this.quality = Integer.parseInt(options.get("quality"));
        }
        if (options.containsKey("gainmap-quality")) {
            this.gainmapQuality = Integer.parseInt(options.get("gainmap-quality"));
        }
        if (options.containsKey("threads")) {
            try {
                int threads = Integer.parseInt(options.get("threads").trim());
                if (threads < 1) {
                    throw new IllegalArgumentException(
                        "UltraHdrTileSink option 'threads' must be at least 1, got '" + options.get("threads") + "'");
                }
                this.threads = threads;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "UltraHdrTileSink option 'threads' must be a positive integer, got '"
                    + options.get("threads") + "'", e);
            }
        }
    }

    @Override
    public String getName() {
        return "ultrahdr";
    }

    /**
     * Advertises UltraHDR support in {@code info.json} via
     * {@link #HDR_PROFILE_URI}.
     *
     * <ul>
     *   <li>V3: prepends {@link #HDR_CONTEXT_URI} to {@code @context},
     *       adds a {@code service} entry and an {@code extraFeatures} entry.</li>
     *   <li>V2: adds the profile URI to the embedded profile {@code supports}
     *       list (plain URI entries are allowed by the V2 spec).</li>
     * </ul>
     */
    @Override
    public InfoExtension getInfoJsonExtension(ImageInfo.IIIFVersion version) {
        java.util.Map<String, Object> service = new java.util.LinkedHashMap<>();
        service.put("id", HDR_PROFILE_URI);
        service.put("type", "Service");
        service.put("profile", HDR_PROFILE_URI);
        InfoExtension own;
        if (version == ImageInfo.IIIFVersion.V3) {
            own = new InfoExtension(
                java.util.List.of(HDR_CONTEXT_URI),
                java.util.List.of(java.util.Collections.unmodifiableMap(service)),
                java.util.List.of(HDR_PROFILE_URI));
        } else {
            own = new InfoExtension(
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(HDR_PROFILE_URI));
        }
        return own.mergedWith(delegateExtension(version));
    }

    private InfoExtension delegateExtension(ImageInfo.IIIFVersion version) {
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
            log.debug("Cannot resolve delegate info.json extension: {}", e.getMessage());
            return InfoExtension.empty();
        }
    }

    // ── TileSink ──────────────────────────────────────────────────────────────

    @Override
    public void saveTile(OutputStream outputStream, BufferedImage image,
                         Map<String, Object> metadata) throws TileSinkException {
        try {
            // 1. Render the primary tile through the delegate sink.
            ByteArrayOutputStream primary = new ByteArrayOutputStream();
            delegate().saveTile(primary, image, metadata);
            byte[] primaryBytes = primary.toByteArray();

            BufferedImage gainmapTile = imageMeta(metadata, GainMapData.META_IMAGE);
            String metadataJson = stringMeta(metadata, GainMapData.META_METADATA);

            if (gainmapTile == null || metadataJson == null) {
                // Source had no gain map (or the Tiler could not crop it) —
                // pass the plain tile through unchanged.
                log.debug("No gain map in metadata; writing plain tile");
                outputStream.write(primaryBytes);
                return;
            }

            // 2. Encode the gain map tile at its native resolution.
            ByteArrayOutputStream gainmapBytes = new ByteArrayOutputStream();
            if (!ImageIO.write(gainmapTile, "jpg", gainmapBytes)) {
                throw new TileSinkException("No JPEG writer for the gain map tile");
            }

            // 3. Assemble the UltraHDR tile.
            byte[] assembled = codec().encode(
                primaryBytes, gainmapBytes.toByteArray(), metadataJson,
                quality, gainmapQuality);

            // 4. Write the assembled tile to the real destination.
            outputStream.write(assembled);
        } catch (IOException e) {
            throw new TileSinkException("Failed to write UltraHDR tile", e);
        } catch (RuntimeException e) {
            throw new TileSinkException("Failed to assemble UltraHDR tile: " + e.getMessage(), e);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Releases the codec if this sink created it, then the delegate if this
     * sink created it.  Injected (shared) instances are left alone.
     */
    @Override
    public void close() {
        if (codec != null && ownsCodec) {
            codec.close();
        }
        codec = null;
        if (delegate instanceof AutoCloseable closeable && ownsDelegate) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("Failed to close delegate sink: {}", e.getMessage());
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private TileSink delegate() {
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

    private GainMapCodec codec() throws TileSinkException {
        if (codec == null) {
            synchronized (this) {
                if (codec == null) {
                    codec = createCodec();
                }
            }
        }
        return codec;
    }

    private int effectiveParallelism() {
        if (threads > 0) {
            return WasmEngine.resolveParallelism(threads, engine);
        }
        return WasmEngine.systemParallelism(engine);
    }

    /**
     * A single lane reuses the JVM-wide shared codec; parallel lanes get a
     * privately owned pooled codec that {@link #close()} shuts down.
     */
    private GainMapCodec createCodec() throws TileSinkException {
        try {
            int parallelism = effectiveParallelism();
            if (parallelism <= 1) {
                GainMapCodec shared = GainMapCodec.shared(engine);
                ownsCodec = false;
                return shared;
            }
            GainMapCodec pooled = new GainMapCodec(engine, parallelism);
            ownsCodec = true;
            return pooled;
        } catch (IOException e) {
            throw new TileSinkException("Cannot initialise the UltraHDR codec: "
                                        + e.getMessage(), e);
        }
    }

    private static BufferedImage imageMeta(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        return metadata.get(key) instanceof BufferedImage image ? image : null;
    }

    private static String stringMeta(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        return metadata.get(key) instanceof String s ? s : null;
    }
}
