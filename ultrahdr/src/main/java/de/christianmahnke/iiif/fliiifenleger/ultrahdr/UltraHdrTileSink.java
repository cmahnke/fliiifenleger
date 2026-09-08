// src/main/java/de/christianmahnke/iiif/fliiifenleger/ultrahdr/UltraHdrTileSink.java
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.Tiler;
import de.christianmahnke.iiif.fliiifenleger.sink.AbstractTileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSinkException;
import de.christianmahnke.iiif.fliiifenleger.source.GainMapData;

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
 * see the {@link de.christianmahnke.iiif.fliiifenleger.source.GainMapData}
 * key constants) is re-encoded, and the ultrahdr WASM codec assembles both
 * into an UltraHDR JPEG that is written to the real output stream.
 *
 * <p>Tiles from sources without a gain map pass through unchanged (the
 * metadata entries are simply absent).
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
 * </ul>
 *
 * <p><b>Threading:</b> the {@code Tiler} generates tiles concurrently; all
 * WASM access is routed through the {@link GainMapCodec}'s dedicated thread.
 */
@AutoService(TileSink.class)
public class UltraHdrTileSink extends AbstractTileSink implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UltraHdrTileSink.class);

    private String delegateName    = "default";
    private String engine          = null;  // null → WasmEngine auto selection
    private int    quality         = 90;
    private int    gainmapQuality  = 85;

    /** Lazily created codec; one per sink instance. */
    private GainMapCodec codec;

    /** Delegate sink; resolved lazily so tests can inject one. */
    private TileSink delegate;

    /** Whether this sink created (and must close) the codec. */
    private boolean ownsCodec = true;

    /** Default constructor (used by ServiceLoader / reflective instantiation). */
    public UltraHdrTileSink() {
    }

    /**
     * Test constructor: uses the given codec and delegate.  Sharing one codec
     * (and therefore one WASM instance) across instances is required — the
     * ultrahdr codec keeps process-global state.
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
    }

    @Override
    public String getName() {
        return "ultrahdr";
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
     * Releases the codec if this sink created it.  Sharing one codec (and
     * therefore one WASM instance) across instances is required — the
     * ultrahdr codec keeps process-global state.
     */
    @Override
    public void close() {
        if (codec != null && ownsCodec) {
            codec.close();
        }
        codec = null;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private TileSink delegate() {
        if (delegate == null) {
            TileSink template = Tiler.SINK_REGISTRY.get(delegateName);
            if (template == null) {
                throw new IllegalArgumentException(
                    "Unknown delegate sink: '" + delegateName + "'");
            }
            try {
                delegate = template.getClass().getConstructor().newInstance();
                // Propagate the format option to the delegate.
                delegate.setOptions(Map.of("format", format));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                    "Cannot instantiate delegate sink '" + delegateName + "'", e);
            }
        }
        return delegate;
    }

    private GainMapCodec codec() throws TileSinkException {
        if (codec == null) {
            try {
                codec = GainMapCodec.shared(engine);
                ownsCodec = false;
            } catch (IOException e) {
                throw new TileSinkException("Cannot initialise the UltraHDR codec: "
                                            + e.getMessage(), e);
            }
        }
        return codec;
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
