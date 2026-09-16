// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.source;

/**
 * Capability interface for {@link ImageSource} implementations that can
 * provide full-range HDR pixels alongside the regular SDR rendition.
 *
 * <p>Sources keep returning an 8-bit SDR {@code BufferedImage} from
 * {@link ImageSource#getImage()} (tone-mapped preview — always safe for
 * legacy consumers); HDR-aware sinks additionally pull the unclamped
 * frame here.
 *
 * <p>The frame may carry an ISO 21496-1 gain map at its own (typically
 * subsampled) resolution (see {@link HdrFrame#gainmap()}): this is how
 * UltraHDR sources expose their gain map.  A {@code null} return means
 * the source currently offers no HDR content (e.g. a plain JPEG fed to
 * an UltraHDR source) — sinks then tile the SDR rendition.
 */
public interface HdrSource {

    /**
     * @return The full HDR frame ({@link HdrFrame}) of the source image.
     * @throws ImageSourceException if the HDR content cannot be provided.
     */
    HdrFrame getHdrFrame() throws ImageSourceException;
}
