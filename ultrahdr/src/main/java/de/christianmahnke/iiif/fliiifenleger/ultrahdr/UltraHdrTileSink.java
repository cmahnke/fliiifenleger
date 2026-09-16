// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/iiif/fliiifenleger/ultrahdr/UltraHdrTileSink.java
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.OptionDescriptor;
import de.christianmahnke.iiif.fliiifenleger.sink.AbstractDelegatingTileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSinkException;
import de.christianmahnke.iiif.fliiifenleger.source.HdrFrame;
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
 * every tile: the delegate sink renders the primary tile, the gain map for
 * the tile region is cropped from the {@link HdrFrame} the {@code Tiler}
 * attaches through the metadata map (see {@link HdrFrame#META_FRAME}),
 * re-encoded, and the ultrahdr WASM codec assembles both into an UltraHDR
 * JPEG that is written to the real output stream.
 *
 * <p>Gain maps stay transparent to sources: when the frame carries one
 * (UltraHDR input) it is cropped through the primary/gainmap resolution
 * ratio; when it does not (true-HDR input such as JXL PQ/HLG) one is
 * derived in-sink (see {@link HdrGainMapDeriver}) before assembly.
 * Tiles from sources without any HDR content pass through unchanged (no
 * frame is attached).
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
public class UltraHdrTileSink extends AbstractDelegatingTileSink implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UltraHdrTileSink.class);

    /** Profile URI advertising UltraHDR gain-map tiles. */
    public static final String HDR_PROFILE_URI = "https://christianmahnke.de/iiif/hdr/";
    /** JSON-LD context for the HDR extension (V3 only). */
    public static final String HDR_CONTEXT_URI = "https://christianmahnke.de/iiif/hdr/context.json";

    private int quality         = 90;
    private int gainmapQuality  = 85;

    /** Lazily created codec; one per sink instance. */
    private GainMapCodec codec;

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
        parseDelegateOptions(options, "UltraHdrTileSink");
        if (options == null) {
            return;
        }
        if (options.containsKey("quality")) {
            this.quality = Integer.parseInt(options.get("quality"));
        }
        if (options.containsKey("gainmap-quality")) {
            this.gainmapQuality = Integer.parseInt(options.get("gainmap-quality"));
        }
    }

    @Override
    public String getName() {
        return "ultrahdr";
    }

    /**
     * Opts into HDR: the {@code Tiler} attaches the source's
     * {@link HdrFrame} to every tile's metadata, which this sink crops
     * (or derives a gain map from) for assembly.
     *
     * <p>The attached frame is shared by reference across the tile tasks
     * of one source image and only read from, so concurrent crops are
     * safe; building it costs one cached float frame per source image.
     */
    @Override
    public boolean supportsHdr() {
        return true;
    }

    @Override
    public String getDescription() {
        return "UltraHDR gain-map tile sink assembling delegate tiles with cropped gain maps via WASM.";
    }

    @Override
    public java.util.List<OptionDescriptor> getAvailableOptions() {
        java.util.ArrayList<OptionDescriptor> options =
                new java.util.ArrayList<>(super.getAvailableOptions());
        options.add(OptionDescriptor.optional("delegate",
                "Name of the delegate sink rendering the primary tile.",
                "default"));
        options.add(OptionDescriptor.optional("runtime",
                "WASM engine selection: auto, chicory, or graalvm.",
                "auto"));
        options.add(OptionDescriptor.optional("quality",
                "JPEG quality for the primary image re-encode.",
                "90", "int"));
        options.add(OptionDescriptor.optional("gainmap-quality",
                "JPEG quality for the gain map re-encode.",
                "85", "int"));
        options.add(OptionDescriptor.optional("threads",
                "Parallel assembly lanes; 1 selects serial execution (default: one per core capped at 4, -Dwasm.lanes=N sets the default).",
                "", "int"));
        return java.util.List.copyOf(options);
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

    // ── TileSink ──────────────────────────────────────────────────────────────

    @Override
    public void saveTile(OutputStream outputStream, BufferedImage image,
                         Map<String, Object> metadata) throws TileSinkException {
        try {
            // 1. Render the primary tile through the delegate sink.
            ByteArrayOutputStream primary = new ByteArrayOutputStream();
            delegate().saveTile(primary, image, metadata);
            byte[] primaryBytes = primary.toByteArray();

            HdrFrame frame = frameMeta(metadata, HdrFrame.META_FRAME);
            if (frame == null) {
                // Source offers no HDR content — pass the plain tile through
                // unchanged.
                log.debug("No HDR frame in metadata; writing plain tile");
                outputStream.write(primaryBytes);
                return;
            }

            // 2. Gain-map tile for this region: crop the carried gain map
            // through the primary/gainmap resolution ratio, or derive one
            // from true-HDR pixels (transparent to sources).
            HdrFrame.GainMap gainmapTile;
            String metadataJson;
            if (frame.hasGainMap()) {
                gainmapTile = cropGainMap(frame, metadata);
                metadataJson = frame.gainmap().metadataJson();
            } else {
                try {
                    HdrGainMapDeriver.Derivation derived = deriveGainMap(frame, image, metadata);
                    gainmapTile = derived.gainMap();
                    metadataJson = derived.metadataJson();
                } catch (IllegalArgumentException e) {
                    log.warn("Gain map derivation failed; writing plain tile: {}", e.getMessage());
                    outputStream.write(primaryBytes);
                    return;
                }
            }

            // 3. Encode the gain map tile at its native (cropped) resolution.
            ByteArrayOutputStream gainmapBytes = new ByteArrayOutputStream();
            if (!ImageIO.write(gainmapTile.toBufferedImage(), "jpg", gainmapBytes)) {
                throw new TileSinkException("No JPEG writer for the gain map tile");
            }

            // 4. Assemble the UltraHDR tile.
            byte[] assembled = codec().encode(
                primaryBytes, gainmapBytes.toByteArray(), metadataJson,
                quality, gainmapQuality);

            // 5. Write the assembled tile to the real destination.
            outputStream.write(assembled);
        } catch (IOException e) {
            throw new TileSinkException("Failed to write UltraHDR tile", e);
        } catch (RuntimeException e) {
            throw new TileSinkException("Failed to assemble UltraHDR tile: " + e.getMessage(), e);
        }
    }

    /**
     * Crops the frame's gain map to the tile region.
     *
     * <p>The region comes from the {@code iiif.region.*} keys the core
     * {@code Tiler} records for every tile (source-image pixels plus scale
     * factor); mapping through the per-axis
     * {@code primary / gainmap} ratio accounts for subsampled (and
     * possibly non-uniformly subsampled) gain maps, rounding outwards so
     * the crop always fully covers the tile — ISO 21496-1 readers scale
     * the gain map back, so slight over-coverage is harmless.
     */
    static HdrFrame.GainMap cropGainMap(HdrFrame frame, Map<String, Object> metadata) {
        HdrFrame.GainMap gainMap = frame.gainmap();
        int[] region = regionMeta(metadata, frame.width(), frame.height());
        int x = region[0];
        int y = region[1];
        int w = region[2];
        int h = region[3];
        int scale = region[4];
        double ratioX = (double) frame.width() / gainMap.width();
        double ratioY = (double) frame.height() / gainMap.height();
        int gx0 = (int) Math.floor(x / (ratioX * scale));
        int gy0 = (int) Math.floor(y / (ratioY * scale));
        int gx1 = (int) Math.ceil((x + w) / (ratioX * scale));
        int gy1 = (int) Math.ceil((y + h) / (ratioY * scale));
        gx0 = Math.max(0, Math.min(gx0, gainMap.width() - 1));
        gy0 = Math.max(0, Math.min(gy0, gainMap.height() - 1));
        gx1 = Math.max(gx0 + 1, Math.min(gx1, gainMap.width()));
        gy1 = Math.max(gy0 + 1, Math.min(gy1, gainMap.height()));
        return gainMap.crop(gx0, gy0, gx1 - gx0, gy1 - gy0);
    }

    /**
     * Derives a gain map from true-HDR pixels for the tile region; the
     * region mapping is the identity (same image) at the tile scale.
     */
    static HdrGainMapDeriver.Derivation deriveGainMap(HdrFrame frame, BufferedImage image,
                                                      Map<String, Object> metadata) {
        int[] region = regionMeta(metadata, frame.width(), frame.height());
        return HdrGainMapDeriver.derive(frame, region[0], region[1], region[2], region[3], image);
    }

    /**
     * Reads the tile region (source-image pixels + scale) recorded by the
     * core {@code Tiler}; falls back to the full frame when absent
     * (e.g. direct API use without the {@code Tiler}).
     */
    private static int[] regionMeta(Map<String, Object> metadata, int fullW, int fullH) {
        int x = intMeta(metadata, "iiif.region.x", 0);
        int y = intMeta(metadata, "iiif.region.y", 0);
        int w = intMeta(metadata, "iiif.region.w", fullW);
        int h = intMeta(metadata, "iiif.region.h", fullH);
        int scale = Math.max(1, intMeta(metadata, "iiif.region.scale", 1));
        x = Math.max(0, Math.min(x, fullW - 1));
        y = Math.max(0, Math.min(y, fullH - 1));
        w = Math.max(1, Math.min(w, fullW - x));
        h = Math.max(1, Math.min(h, fullH - y));
        return new int[]{x, y, w, h, scale};
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
        closeDelegate();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

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

    private static HdrFrame frameMeta(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        return metadata.get(key) instanceof HdrFrame frame ? frame : null;
    }

    private static int intMeta(Map<String, Object> metadata, String key, int fallback) {
        if (metadata == null) {
            return fallback;
        }
        return metadata.get(key) instanceof Number n ? n.intValue() : fallback;
    }
}
