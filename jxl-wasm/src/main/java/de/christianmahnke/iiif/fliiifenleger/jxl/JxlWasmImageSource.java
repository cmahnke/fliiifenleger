// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/iiif/fliiifenleger/jxl/JxlWasmImageSource.java
package de.christianmahnke.iiif.fliiifenleger.jxl;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.source.AbstractImageSource;
import de.christianmahnke.iiif.fliiifenleger.source.HdrFrame;
import de.christianmahnke.iiif.fliiifenleger.source.HdrSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSourceException;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.RasterFormatException;
import java.net.URL;
import java.util.Map;

/**
 * JPEG XL image source backed by the {@code jxl_wasm} module (pure-Rust
 * jxl-oxide decoder, no native libraries).
 *
 * <p>Registers under the same {@code "jxl"} name as core's NightMonkeys-based
 * {@code JxlImageSource} — the two must never share a classpath (the native
 * profile excludes the NightMonkeys plugin).  Intended for the GraalVM-native
 * build, where FFM-based JNI plugins cannot run; on regular JVMs the
 * NightMonkeys variant remains the default.
 */
/**
 * JPEG XL image source backed by the {@code jxl_wasm} module (pure-Rust
 * jxl-oxide decoder, no native libraries).
 *
 * <p>Registers under the same {@code "jxl"} name as core's NightMonkeys-based
 * {@code JxlImageSource} — the two must never share a classpath (the native
 * profile excludes the NightMonkeys plugin).  Intended for the GraalVM-native
 * build, where FFM-based JNI plugins cannot run; on regular JVMs the
 * NightMonkeys variant remains the default.
 *
 * <p>Also implements {@link HdrSource}: the SDR rendition ({@link #getImage})
 * is decoded eagerly on {@link #load}, while the full-range frame
 * ({@link #getHdrFrame}) decodes lazily on first request, so SDR-only
 * consumers never pay for HDR.
 */
@AutoService(ImageSource.class)
public class JxlWasmImageSource extends AbstractImageSource implements ImageSource, HdrSource {

    private BufferedImage image;
    private byte[] jxlBytes;
    private HdrFrame hdrFrame;
    private static final String NAME = "jxl";

    @Override
    public BufferedImage getImage() {
        return image;
    }

    @Override
    public void load(URL url) throws ImageSourceException {
        this.url = url;
        loadImage();
    }

    @Override
    public int getWidth() {
        return image.getWidth();
    }

    @Override
    public int getHeight() {
        return image.getHeight();
    }

    private void loadImage() throws ImageSourceException {
        if (this.url == null) {
            throw new IllegalStateException("URL has not been set for JxlWasmImageSource.");
        }
        try {
            try (var in = AbstractImageSource.getInputStream(this.url)) {
                jxlBytes = in.readAllBytes();
            }
        } catch (Exception e) {
            throw new ImageSourceException("Could not read JXL image from path: " + url, e);
        }
        try (JxlDecoder decoder = new JxlDecoder((String) null, 1)) {
            this.image = toBufferedImage(decoder.decode(jxlBytes));
        } catch (JxlWasmException e) {
            throw new ImageSourceException("Could not decode JXL image from path: " + url, e);
        } catch (Exception e) {
            throw new ImageSourceException("Could not read JXL image from path: " + url, e);
        }
        this.hdrFrame = null;
    }

    @Override
    public synchronized HdrFrame getHdrFrame() throws ImageSourceException {
        if (this.url == null || this.jxlBytes == null) {
            throw new IllegalStateException("URL has not been set for JxlWasmImageSource.");
        }
        if (hdrFrame == null) {
            try (JxlDecoder decoder = new JxlDecoder((String) null, 1)) {
                hdrFrame = decoder.decodeHdr(jxlBytes);
            } catch (JxlWasmException e) {
                throw new ImageSourceException("Could not decode JXL HDR frame from path: " + url, e);
            } catch (Exception e) {
                throw new ImageSourceException("Could not read JXL image from path: " + url, e);
            }
        }
        return hdrFrame;
    }

    /**
     * Convert interleaved 8-bit decoder output to a {@link BufferedImage}.
     * The decoder emits straight (non-premultiplied) samples in RGB(A) order.
     */
    static BufferedImage toBufferedImage(JxlDecoder.DecodedImage decoded) throws ImageSourceException {
        int w = decoded.width();
        int h = decoded.height();
        byte[] px = decoded.pixels();
        switch (decoded.channels()) {
            case 1 -> {
                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
                byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                System.arraycopy(px, 0, data, 0, px.length);
                return image;
            }
            case 3 -> {
                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
                byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                for (int i = 0, n = w * h; i < n; i++) {
                    data[3 * i]     = px[3 * i + 2];
                    data[3 * i + 1] = px[3 * i + 1];
                    data[3 * i + 2] = px[3 * i];
                }
                return image;
            }
            case 4 -> {
                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_4BYTE_ABGR);
                byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                for (int i = 0, n = w * h; i < n; i++) {
                    data[4 * i]     = px[4 * i + 3];
                    data[4 * i + 1] = px[4 * i + 2];
                    data[4 * i + 2] = px[4 * i + 1];
                    data[4 * i + 3] = px[4 * i];
                }
                return image;
            }
            default -> throw new ImageSourceException(
                "Unsupported JXL channel count: " + decoded.channels());
        }
    }

    @Override
    public BufferedImage crop(int x, int y, int width, int height, double scale) throws ImageSourceException {
        getImage();
        if (image == null) {
            loadImage();
        }
        BufferedImage cropped;
        try {
            cropped = image.getSubimage(x, y, width, height);
        } catch (RasterFormatException e) {
            throw new ImageSourceException(String.format("Crop region [x=%d, y=%d, width=%d, height=%d] is outside the image bounds [width=%d, height=%d].", x, y, width, height, image.getWidth(), image.getHeight()), e);
        }
        if (scale == 1.0) {
            return cropped;
        }

        int newWidth = (int) Math.ceil(width / scale);
        int newHeight = (int) Math.ceil(height / scale);

        // See https://github.com/usnistgov/pyramidio/issues/7#issuecomment-369357845
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, cropped.getType() == 0 ? 5 : cropped.getType());
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(cropped, 0, 0, newWidth, newHeight, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    @Override
    public Map<String, Object> getMetadata() {
        // Like JxlImageSource, the decoder exposes no EXIF/XMP metadata.
        return Map.of(
            "jxl_decoder", "jxl-wasm"
        );
    }

    public String getName() {
        return JxlWasmImageSource.NAME;
    }

    @Override
    public String getDescription() {
        return "JPEG XL source via the jxl-wasm module (pure-Rust jxl-oxide decoder, GraalVM-native path, no options).";
    }
}
