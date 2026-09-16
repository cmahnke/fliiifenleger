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
 * frame here.  This mirrors the {@code GainMapSource} capability in the
 * {@code ultrahdr} module, but carries scene/display-referred floats
 * instead of container gain-map bytes.
 */
public interface HdrSource {

    /**
     * @return The full HDR frame ({@link HdrFrame}) of the source image.
     * @throws ImageSourceException if the HDR content cannot be provided.
     */
    HdrFrame getHdrFrame() throws ImageSourceException;
}
