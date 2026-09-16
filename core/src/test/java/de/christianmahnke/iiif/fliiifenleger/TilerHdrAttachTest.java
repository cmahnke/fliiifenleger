// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSinkException;
import de.christianmahnke.iiif.fliiifenleger.source.HdrFrame;
import de.christianmahnke.iiif.fliiifenleger.source.HdrSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSourceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.OutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HDR metadata attachment in {@link Tiler#attachHdrMetadata}:
 * HDR-aware sinks receive the frame, unaware sinks see (and pay for)
 * nothing.
 */
@DisplayName("Tiler HDR attach")
class TilerHdrAttachTest {

    /** Minimal HDR-capable source around a fixed frame. */
    static class StubHdrSource implements ImageSource, HdrSource {
        private final HdrFrame frame;
        private int hdrCalls;

        StubHdrSource(HdrFrame frame) {
            this.frame = frame;
        }

        @Override
        public String getName() {
            return "stub-hdr";
        }

        @Override
        public BufferedImage getImage() {
            return frame.toBufferedImage();
        }

        @Override
        public URL getUrl() {
            return null;
        }

        @Override
        public void load(URL url) {
        }

        @Override
        public int getWidth() {
            return frame.width();
        }

        @Override
        public int getHeight() {
            return frame.height();
        }

        @Override
        public BufferedImage crop(int x, int y, int width, int height, double scale) {
            return getImage().getSubimage(x, y, width, height);
        }

        @Override
        public Map<String, Object> getMetadata() {
            return Map.of();
        }

        @Override
        public HdrFrame getHdrFrame() {
            hdrCalls++;
            return frame;
        }
    }

    /** Recording sink with configurable HDR support. */
    static class StubSink implements TileSink {
        private final boolean hdr;
        Map<String, Object> lastMetadata;

        StubSink(boolean hdr) {
            this.hdr = hdr;
        }

        @Override
        public void saveTile(OutputStream outputStream, BufferedImage image,
                             Map<String, Object> metadata) {
            lastMetadata = new HashMap<>(metadata);
        }

        @Override
        public String getFormatExtension() {
            return "jpg";
        }

        @Override
        public String getName() {
            return "stub";
        }

        @Override
        public boolean supportsHdr() {
            return hdr;
        }
    }

    private static ImageInfo imageInfo(ImageSource source) throws Exception {
        File page = new File("src/test/resources/images/page011.jpg");
        assertTrue(page.exists(), "Test image file must exist");
        return new ImageInfo(source, 512, 512, 1,
            "http://localhost/iiif/", ImageInfo.IIIFVersion.V2);
    }

    @Test
    @DisplayName("HDR-aware sink receives frame, transfer and primaries")
    void hdrSinkReceivesFrame() throws Exception {
        HdrFrame frame = new HdrFrame(4, 4, 3, new float[4 * 4 * 3],
            HdrFrame.TransferFunction.PQ, HdrFrame.Primaries.BT2020);
        StubHdrSource source = new StubHdrSource(frame);
        StubSink sink = new StubSink(true);
        Map<String, Object> meta = new HashMap<>();
        Tiler.attachHdrMetadata(imageInfo(source), sink, meta);
        assertSame(frame, meta.get(HdrFrame.META_FRAME));
        assertEquals("PQ", meta.get(HdrFrame.META_TRANSFER));
        assertEquals("BT2020", meta.get(HdrFrame.META_PRIMARIES));
        assertEquals(1, source.hdrCalls);
    }

    @Test
    @DisplayName("unaware sink sees no HDR keys and triggers no decode")
    void unawareSinkSeesNothing() throws Exception {
        HdrFrame frame = new HdrFrame(4, 4, 3, new float[4 * 4 * 3],
            HdrFrame.TransferFunction.PQ, HdrFrame.Primaries.BT2020);
        StubHdrSource source = new StubHdrSource(frame);
        StubSink sink = new StubSink(false);
        Map<String, Object> meta = new HashMap<>();
        Tiler.attachHdrMetadata(imageInfo(source), sink, meta);
        assertTrue(meta.isEmpty());
        assertEquals(0, source.hdrCalls);
    }

    @Test
    @DisplayName("SDR source yields no HDR keys even for aware sinks")
    void sdrSourceYieldsNothing() throws Exception {
        File page = new File("src/test/resources/images/page011.jpg");
        ImageSource source = new de.christianmahnke.iiif.fliiifenleger.source.DefaultImageSource();
        source.load(page.toURI().toURL());
        StubSink sink = new StubSink(true);
        Map<String, Object> meta = new HashMap<>();
        Tiler.attachHdrMetadata(imageInfo(source), sink, meta);
        assertTrue(meta.isEmpty());
    }
}
