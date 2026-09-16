// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/jc2pa/C2paTileSink.java
package de.christianmahnke.jc2pa;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmEngine;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.OptionDescriptor;
import de.christianmahnke.iiif.fliiifenleger.sink.AbstractDelegatingTileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSinkException;
import de.christianmahnke.iiif.fliiifenleger.ImageInfo;

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
  *   <li>{@code trust-anchor} — absolute URI advertised as {@code trustAnchor}
  *       in the {@code https://christianmahnke.de/iiif/c2pa/} service entry of
  *       a V3 {@code info.json}. Requires {@code --iiif-version V3}; fails with
  *       Image API 2 since V2 offers no place for namespaced options.</li>
  *   <li>{@code threads} — parallel signing lanes (default: one per available
  *       processor capped at 4; {@code 1} selects serial execution). Each lane pairs one
  *       interpreter instance with its own thread;
  *       {@code -Dwasm.lanes=N} sets the default when unset.</li>
  * </ul>
 *
 * <p><b>info.json:</b> always advertises {@code https://christianmahnke.de/iiif/c2pa/}
 * (V3 service + {@code extraFeatures}, V2 {@code supports} entry).
 *
  * <p><b>Threading:</b> the {@code Tiler} generates tiles concurrently; all
  * WASM access is routed through the {@link TileSigner}'s lane pool (one lane
  * per available processor capped at 4 by default, serial with {@code threads=1}).
 *
 * <p><b>Note:</b> c2pa-rs keeps process-global state — do not run multiple
 * C2PA sinks (or other {@code C2paWasm} users) in the same JVM.
 */
@AutoService(TileSink.class)
public class C2paTileSink extends AbstractDelegatingTileSink implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(C2paTileSink.class);

    private static final String DEFAULT_CLAIM_GENERATOR = "fliiifenleger";

    /** Profile URI advertising C2PA-signed tiles (V3 service + V2 supports entry). */
    public static final String C2PA_PROFILE_URI = "https://christianmahnke.de/iiif/c2pa/";
    /** JSON-LD context for the C2PA extension (V3 only, prepended to {@code @context}). */
    public static final String C2PA_CONTEXT_URI = "https://christianmahnke.de/iiif/c2pa/context.json";

    private Path   certPath       = null;
    private Path   keyPath        = null;
    private String alg            = "es256";
    private String tsaUrl         = null;
    private String certName       = "fliiifenleger";
    private String claimGenerator = DEFAULT_CLAIM_GENERATOR;
    private String trustAnchor     = null;

    /** Lazily created signer; one per sink instance. */
    private TileSigner signer;

    /** Whether this sink created (and must close) the signer. */
    private boolean ownsSigner = true;

    // ── Configuration ─────────────────────────────────────────────────────────

    @Override
    public void setOptions(Map<String, String> options) {
        super.setOptions(options);
        parseDelegateOptions(options, "C2paTileSink");
        if (options == null) {
            return;
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
        if (options.containsKey("trust-anchor")) {
            String value = options.get("trust-anchor");
            if (value == null || value.isBlank()) {
                this.trustAnchor = null;
            } else {
                String trimmed = value.trim();
                try {
                    java.net.URI uri = new java.net.URI(trimmed);
                    if (!uri.isAbsolute()) {
                        throw new IllegalArgumentException(
                            "C2paTileSink option 'trust-anchor' must be an absolute URI, got '" + trimmed + "'");
                    }
                } catch (java.net.URISyntaxException e) {
                    throw new IllegalArgumentException(
                        "C2paTileSink option 'trust-anchor' must be an absolute URI, got '" + trimmed + "'", e);
                }
                this.trustAnchor = trimmed;
            }
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

    @Override
    public String getDescription() {
        return "C2PA-signing tile sink decorating a delegate sink with per-tile manifests via WASM.";
    }

    @Override
    public java.util.List<OptionDescriptor> getAvailableOptions() {
        java.util.ArrayList<OptionDescriptor> options =
                new java.util.ArrayList<>(super.getAvailableOptions());
        options.add(OptionDescriptor.optional("delegate",
                "Name of the delegate sink rendering the tile before signing.",
                "default"));
        options.add(OptionDescriptor.optional("runtime",
                "WASM engine selection: auto, chicory, or graalvm.",
                "auto"));
        options.add(OptionDescriptor.optional("cert",
                "Path to PEM certificate chain file (requires 'key' for real signatures).",
                "", "path"));
        options.add(OptionDescriptor.optional("key",
                "Path to PEM private key file (requires 'cert' for real signatures).",
                "", "path"));
        options.add(OptionDescriptor.optional("alg",
                "Signing algorithm for real key material.",
                "es256"));
        options.add(OptionDescriptor.optional("tsa",
                "Timestamp authority URL (optional, only with cert/key).",
                "", "uri"));
        options.add(OptionDescriptor.optional("cert-name",
                "Common name for the ephemeral certificate when no cert/key is given.",
                "fliiifenleger"));
        options.add(OptionDescriptor.optional("claim-generator",
                "Claim generator string written into the manifest.",
                "fliiifenleger"));
        options.add(OptionDescriptor.optional("trust-anchor",
                "Absolute URI advertised as trustAnchor in a V3 info.json service entry (requires IIIF Image API 3).",
                "", "uri"));
        options.add(OptionDescriptor.optional("threads",
                "Parallel signing lanes; 1 selects serial execution (default: one per core capped at 4, -Dwasm.lanes=N sets the default).",
                "", "int"));
        return java.util.List.copyOf(options);
    }

    /**
     * Advertises C2PA support in {@code info.json} via
     * {@link #C2PA_PROFILE_URI}.
     *
     * <ul>
     *   <li>V3: prepends {@link #C2PA_CONTEXT_URI} to {@code @context},
     *       adds a {@code service} entry and an {@code extraFeatures} entry.
     *       When {@code trust-anchor} is set, it is written as the namespaced
     *       {@code trustAnchor} property of that service entry.</li>
     *   <li>V2: adds the profile URI to the embedded profile {@code supports}
     *       list. A {@code trust-anchor} cannot be expressed (fixed
     *       {@code @context}, no place for namespaced properties) and fails
     *       with {@link IllegalArgumentException}.</li>
     * </ul>
     */
    @Override
    public InfoExtension getInfoJsonExtension(ImageInfo.IIIFVersion version) {
        if (version == ImageInfo.IIIFVersion.V2 && trustAnchor != null) {
            throw new IllegalArgumentException(
                "C2PA 'trust-anchor' requires IIIF Image API 3: Image API 2 has no way "
                + "to express namespaced info.json options (fixed @context).");
        }
        java.util.Map<String, Object> service = new java.util.LinkedHashMap<>();
        service.put("id", C2PA_PROFILE_URI);
        service.put("type", "Service");
        service.put("profile", C2PA_PROFILE_URI);
        InfoExtension own;
        if (version == ImageInfo.IIIFVersion.V3) {
            if (trustAnchor != null) {
                service.put("trustAnchor", trustAnchor);
            }
            own = new InfoExtension(
                java.util.List.of(C2PA_CONTEXT_URI),
                java.util.List.of(java.util.Collections.unmodifiableMap(service)),
                java.util.List.of(C2PA_PROFILE_URI));
        } else {
            own = new InfoExtension(
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(C2PA_PROFILE_URI));
        }
        return own.mergedWith(delegateExtension(version));
    }

    /** Default constructor (used by ServiceLoader / reflective instantiation). */
    public C2paTileSink() {
    }

    // ── Test support ──────────────────────────────────────────────────────────

    /**
     * Test constructor: uses the given signer and delegate.  The signer is
     * shared (not owned): serial use, or a signer whose own lane pool was
     * sized for the test.
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

    private TileSigner signer() throws TileSinkException {
        if (signer == null) {
            synchronized (this) {
                if (signer == null) {
                    signer = createSigner();
                }
            }
        }
        return signer;
    }

    private int effectiveParallelism() {
        if (threads > 0) {
            return WasmEngine.resolveParallelism(threads, engine);
        }
        return WasmEngine.systemParallelism(engine);
    }

    private TileSigner createSigner() throws TileSinkException {
        try {
            TileSigner created = new TileSigner(engine, effectiveParallelism());
            ownsSigner = true;
            return created;
        } catch (IOException e) {
            throw new TileSinkException("Cannot initialise the C2PA signer: "
                                        + e.getMessage(), e);
        }
    }

    /**
     * Releases the signer if this sink created it, then the delegate if this
     * sink created it.  Injected (shared) instances are left alone.
     */
    @Override
    public void close() {
        if (signer != null && ownsSigner) {
            signer.close();
        }
        signer = null;
        closeDelegate();
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
        // A c2pa.created action must carry a digitalSourceType, otherwise
        // validators report assertion.action.malformed.  Tiles are
        // algorithmically cropped and re-encoded without changing the main
        // content of the source image.
        json.append("          { \"action\": \"c2pa.created\", \"digitalSourceType\": "
            + "\"http://cv.iptc.org/newscodes/digitalsourcetype/algorithmicallyEnhanced\" }\n");
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
