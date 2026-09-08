// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/jc2pa/C2paTileSink.java
package de.christianmahnke.jc2pa;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.Tiler;
import de.christianmahnke.iiif.fliiifenleger.sink.AbstractTileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSinkException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A {@link TileSink} decorator that signs every tile with a C2PA manifest
 * before it is written.
 *
 * <p>The tile is first rendered by a delegate sink (by default the
 * {@code default} sink), the resulting bytes are signed through the
 * {@link TileSigner} service, and the signed bytes are written to the real
 * output stream.
 *
 * <p>The per-tile manifest contains the tile's region in source-image
 * coordinates (provided by the {@code Tiler} via the
 * {@code iiif.region.*} metadata entries) as a custom
 * {@code org.projektemacher.iiif.region} assertion.
 *
 * <p><b>Options</b> (via {@code --sink-opt}):
 * <ul>
 *   <li>{@code delegate} — name of the delegate sink (default: {@code default}).</li>
 *   <li>{@code format} — tile format (default: {@code jpg}; handled by the
 *       base class).</li>
 *   <li>{@code runtime} — WASM engine selection: {@code auto} (default),
 *       {@code chicory}, or {@code graalvm}.</li>
 *   <li>{@code cert} / {@code key} — paths to PEM certificate chain and
 *       private key files.  When both are set, tiles are signed with real
 *       key material ({@code alg} selects the algorithm, default
 *       {@code es256}; optional {@code tsa} timestamp authority URL).</li>
 *   <li>{@code cert-name} — common name for the ephemeral certificate used
 *       when no {@code cert}/{@code key} is given (default:
 *       {@code fliiifenleger}).  Ephemeral signatures are for testing only.</li>
 *   <li>{@code claim-generator} — claim generator string written into the
 *       manifest.</li>
 * </ul>
 *
 * <p><b>Threading:</b> the {@code Tiler} generates tiles concurrently; all
 * WASM access is routed through the {@link TileSigner}'s dedicated thread.
 *
 * <p><b>Note:</b> c2pa-rs keeps process-global state — do not run multiple
 * C2PA sinks (or other {@code C2paWasm} users) in the same JVM.
 */
@AutoService(TileSink.class)
public class C2paTileSink extends AbstractTileSink implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(C2paTileSink.class);

    private static final String DEFAULT_CLAIM_GENERATOR = "fliiifenleger";

    private String delegateName   = "default";
    private String engine         = null;   // null → WasmEngine auto selection
    private Path   certPath       = null;
    private Path   keyPath        = null;
    private String alg            = "es256";
    private String tsaUrl         = null;
    private String certName       = "fliiifenleger";
    private String claimGenerator = DEFAULT_CLAIM_GENERATOR;

    /** Lazily created signer; one per sink instance. */
    private TileSigner signer;

    /** Whether this sink created (and must close) the signer. */
    private boolean ownsSigner = true;

    /** Delegate sink; resolved lazily so tests can inject one. */
    private TileSink delegate;

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
        if (options.containsKey("cert")) {
            this.certPath = Path.of(options.get("cert"));
        }
        if (options.containsKey("key")) {
            this.keyPath = Path.of(options.get("key"));
        }
        if (options.containsKey("alg")) {
            this.alg = options.get("alg");
        }
        if (options.containsKey("tsa")) {
            this.tsaUrl = options.get("tsa");
        }
        if (options.containsKey("cert-name")) {
            this.certName = options.get("cert-name");
        }
        if (options.containsKey("claim-generator")) {
            this.claimGenerator = options.get("claim-generator");
        }

        if ((certPath == null) != (keyPath == null)) {
            throw new IllegalArgumentException(
                "C2paTileSink requires both 'cert' and 'key' options (or neither "
                + "for ephemeral signing)");
        }
    }

    @Override
    public String getName() {
        return "c2pa";
    }

    /** Default constructor (used by ServiceLoader / reflective instantiation). */
    public C2paTileSink() {
    }

    // ── Test support ──────────────────────────────────────────────────────────

    /**
     * Test constructor: uses the given signer and delegate.  Sharing one
     * signer across instances is required — c2pa-rs keeps process-global
     * state, so only one live WASM instance may exist per JVM.
     *
     * @param signer   The signer to use (shared ownership).
     * @param delegate The delegate sink.
     */
    C2paTileSink(TileSigner signer, TileSink delegate) {
        this.signer     = signer;
        this.delegate   = delegate;
        this.ownsSigner = false;
    }

    // ── TileSink ──────────────────────────────────────────────────────────────

    @Override
    public void saveTile(OutputStream outputStream, BufferedImage image,
                         Map<String, Object> metadata) throws TileSinkException {
        try {
            // 1. Render the tile through the delegate sink into a buffer.
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            delegate().saveTile(buffer, image, metadata);
            byte[] tileBytes = buffer.toByteArray();

            // 2. Sign the tile bytes with a per-tile manifest.
            byte[] signed = (certPath == null)
                ? signer().signEphemeral(tileBytes, mimeFormat(),
                                         manifestFor(metadata), certName)
                : signer().sign(tileBytes, mimeFormat(),
                                manifestFor(metadata), certPem(), keyPem(),
                                alg, tsaUrl);

            // 3. Write the signed bytes to the real destination.
            outputStream.write(signed);
        } catch (IOException e) {
            throw new TileSinkException("Failed to write C2PA-signed tile", e);
        } catch (RuntimeException e) {
            throw new TileSinkException("Failed to sign tile: " + e.getMessage(), e);
        }
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

    private TileSigner signer() throws TileSinkException {
        if (signer == null) {
            try {
                signer = new TileSigner(engine);
                ownsSigner = true;
            } catch (IOException e) {
                throw new TileSinkException("Cannot initialise the C2PA signer: "
                                            + e.getMessage(), e);
            }
        }
        return signer;
    }

    /**
     * Releases the signer if this sink created it.  Sharing one signer (and
     * therefore one WASM instance) across instances is required — c2pa-rs
     * keeps process-global state.
     */
    @Override
    public void close() {
        if (signer != null && ownsSigner) {
            signer.close();
        }
        signer = null;
    }

    private byte[] certPem() throws TileSinkException {
        if (certPath == null) {
            return null;
        }
        try {
            return Files.readAllBytes(certPath);
        } catch (IOException e) {
            throw new TileSinkException("Cannot read certificate file " + certPath, e);
        }
    }

    private byte[] keyPem() throws TileSinkException {
        if (keyPath == null) {
            return null;
        }
        try {
            return Files.readAllBytes(keyPath);
        } catch (IOException e) {
            throw new TileSinkException("Cannot read key file " + keyPath, e);
        }
    }

    /** MIME type matching the configured tile format. */
    private String mimeFormat() {
        return switch (format.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png"         -> "image/png";
            case "tif", "tiff" -> "image/tiff";
            case "gif"         -> "image/gif";
            case "webp"        -> "image/webp";
            case "avif"        -> "image/avif";
            case "jp2"         -> "image/jp2";
            default            -> "image/" + format.toLowerCase();
        };
    }

    /**
     * Build the per-tile manifest JSON, embedding the tile region from the
     * Tiler's metadata as a custom assertion.
     */
    private String manifestFor(Map<String, Object> metadata) {
        StringBuilder json = new StringBuilder(256);
        json.append("{\n");
        json.append("  \"claim_generator\": \"").append(escape(claimGenerator)).append("\",\n");
        json.append("  \"title\": \"").append(escape(titleFor(metadata))).append("\",\n");
        json.append("  \"assertions\": [\n");
        // The first action must be created/opened per the C2PA spec — tiles
        // are new assets derived from the source image.
        json.append("    {\n");
        json.append("      \"label\": \"c2pa.actions\",\n");
        json.append("      \"data\": {\n");
        json.append("        \"actions\": [\n");
        json.append("          { \"action\": \"c2pa.created\" }\n");
        json.append("        ]\n");
        json.append("      }\n");
        json.append("    },\n");
        json.append("    {\n");
        json.append("      \"label\": \"org.projektemacher.iiif.region\",\n");
        json.append("      \"data\": {\n");
        json.append("        \"x\": ").append(intMeta(metadata, "iiif.region.x")).append(",\n");
        json.append("        \"y\": ").append(intMeta(metadata, "iiif.region.y")).append(",\n");
        json.append("        \"w\": ").append(intMeta(metadata, "iiif.region.w")).append(",\n");
        json.append("        \"h\": ").append(intMeta(metadata, "iiif.region.h")).append(",\n");
        json.append("        \"scale\": ").append(intMeta(metadata, "iiif.region.scale")).append("\n");
        json.append("      }\n");
        json.append("    }\n");
        json.append("  ]\n");
        json.append("}");
        return json.toString();
    }

    private String titleFor(Map<String, Object> metadata) {
        int x = intMeta(metadata, "iiif.region.x");
        int y = intMeta(metadata, "iiif.region.y");
        int w = intMeta(metadata, "iiif.region.w");
        int h = intMeta(metadata, "iiif.region.h");
        if (x == 0 && y == 0 && w == 0 && h == 0) {
            return "IIIF tile (full image)";
        }
        return String.format("IIIF tile %d,%d,%d,%d", x, y, w, h);
    }

    private static int intMeta(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return 0;
        }
        Object value = metadata.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
