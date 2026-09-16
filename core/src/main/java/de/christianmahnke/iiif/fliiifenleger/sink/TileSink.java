// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

package de.christianmahnke.iiif.fliiifenleger.sink;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;

import java.io.OutputStream;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;

/**
 * A sink for writing generated IIIF image tiles.
 */
public interface TileSink {
    /**
     * Saves a given image tile to the specified path.
     *
     * @param outputStream The destination stream for the tile.
     * @param image The image data to save.
     * @throws TileSinkException if an error occurs during saving.
     */
    void saveTile(OutputStream outputStream, BufferedImage image, Map<String, Object> metadata) throws TileSinkException;

    /**
     * @return The file extension for the format this sink writes (e.g., "jpg", "png").
     */
    String getFormatExtension();

    /**
     * @return The unique name for this sink implementation (e.g., "default", "jpeg").
     */
    String getName();

    /**
     * Sets configuration options for this tile sink.
     *
     * @param options A map of key-value pairs.
     */
    default void setOptions(Map<String, String> options) {}

    /**
     * Whether this sink consumes HDR content ({@code hdr.*} metadata keys)
     * when the source offers it.
     *
     * <p>The {@code Tiler} only materializes and attaches HDR payloads for
     * sinks opting in here, so unaware sinks neither see nor pay for HDR.
     * Additive default — existing implementations behave exactly as before.
     *
     * @return {@code true} if this sink reads HDR metadata keys.
     */
    default boolean supportsHdr() {
        return false;
    }

    /**
     * Contributes sink-specific entries to the {@code info.json} document.
     *
     * <p>Called by the {@code Tiler} after the base document has been built.
     * The default implementation advertises nothing.
     *
     * @param version The IIIF Image API version being generated.
     * @return The extension fragment; never {@code null}.
     * @throws IllegalArgumentException if the current options cannot be
     *         expressed for the requested version (e.g. a namespaced
     *         {@code trust-anchor} with Image API 2, whose fixed
     *         {@code @context} offers no place for extension properties).
     */
    default InfoExtension getInfoJsonExtension(ImageInfo.IIIFVersion version) {
        return InfoExtension.empty();
    }

    /**
     * Sink contribution to {@code info.json}.
     *
     * @param contexts Additional JSON-LD context URIs (V3 only, prepended
     *                 before the IIIF context; must be empty for V2).
     * @param services Service entries appended to {@code service} (V3) or
     *                 {@code service} (V2, without namespaced properties).
     * @param features Feature URIs appended to {@code extraFeatures} (V3) or
     *                 to the embedded profile {@code supports} list (V2).
     */
    record InfoExtension(java.util.List<String> contexts,
                         java.util.List<Map<String, Object>> services,
                         java.util.List<String> features) {
        public InfoExtension {
            contexts = contexts == null ? java.util.List.of() : java.util.List.copyOf(contexts);
            services = services == null ? java.util.List.of() : java.util.List.copyOf(services);
            features = features == null ? java.util.List.of() : java.util.List.copyOf(features);
        }

        public static InfoExtension empty() {
            return new InfoExtension(java.util.List.of(), java.util.List.of(), java.util.List.of());
        }

        public boolean isEmpty() {
            return contexts.isEmpty() && services.isEmpty() && features.isEmpty();
        }

        public InfoExtension mergedWith(InfoExtension other) {
            if (other == null || other.isEmpty()) {
                return this;
            }
            if (this.isEmpty()) {
                return other;
            }
            java.util.LinkedHashSet<String> ctx = new java.util.LinkedHashSet<>(this.contexts);
            ctx.addAll(other.contexts);
            java.util.ArrayList<Map<String, Object>> svc = new java.util.ArrayList<>(this.services);
            for (Map<String, Object> s : other.services) {
                if (!svc.contains(s)) {
                    svc.add(s);
                }
            }
            java.util.LinkedHashSet<String> feat = new java.util.LinkedHashSet<>(this.features);
            feat.addAll(other.features);
            return new InfoExtension(java.util.List.copyOf(ctx), java.util.List.copyOf(svc), java.util.List.copyOf(feat));
        }
    }

}