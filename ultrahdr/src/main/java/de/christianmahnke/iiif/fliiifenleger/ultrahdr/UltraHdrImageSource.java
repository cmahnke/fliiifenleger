// src/main/java/de/christianmahnke/iiif/fliiifenleger/ultrahdr/UltraHdrImageSource.java
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.source.DefaultImageSource;
import de.christianmahnke.iiif.fliiifenleger.source.GainMapData;
import de.christianmahnke.iiif.fliiifenleger.source.GainMapSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSourceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link ImageSource} for UltraHDR JPEGs: the primary (SDR) image is
 * decoded through the regular ImageIO pipeline, while the gain map and its
 * ISO 21496-1 metadata are split out via the ultrahdr WASM codec and exposed
 * through the {@link GainMapSource} capability.
 *
 * <p>Plain JPEGs without a gain map are handled gracefully: the source
 * behaves like {@link DefaultImageSource} and {@link #getGainMap()} returns
 * {@code null}.
 *
 * <p><b>Threading:</b> the WASM codec keeps thread-affine state — see
 * {@link GainMapCodec} for the single-thread architecture.
 */
@AutoService(ImageSource.class)
public class UltraHdrImageSource implements ImageSource, GainMapSource {

    private static final Logger log = LoggerFactory.getLogger(UltraHdrImageSource.class);

    /** WASM engine selection ({@code auto}/{@code chicory}/{@code graalvm}). */
    private String engine;

    private final DefaultImageSource delegate = new DefaultImageSource();

    private URL url;
    private byte[] sourceBytes;
    private GainMapData gainMap;
    private boolean splitDone;

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Override
    public String getName() {
        return "ultrahdr";
    }

    @Override
    public void load(URL url) throws ImageSourceException {
        this.url = url;
        this.gainMap = null;
        try {
            sourceBytes = url.openStream().readAllBytes();
        } catch (IOException e) {
            throw new ImageSourceException("Cannot read UltraHDR source " + url, e);
        }
    }

    @Override
    public BufferedImage getImage() throws ImageSourceException {
        ensureSplit();
        return delegate.getImage();
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public int getWidth() {
        try {
            ensureSplit();
        } catch (ImageSourceException e) {
            // The interface does not declare checked exceptions for the
            // dimension accessors — degrade to the uninitialised behaviour.
            throw new IllegalStateException("UltraHDR source could not be split: "
                                           + e.getMessage(), e);
        }
        return delegate.getWidth();
    }

    @Override
    public int getHeight() {
        try {
            ensureSplit();
        } catch (ImageSourceException e) {
            throw new IllegalStateException("UltraHDR source could not be split: "
                                           + e.getMessage(), e);
        }
        return delegate.getHeight();
    }

    @Override
    public BufferedImage crop(int x, int y, int width, int height, double scale)
            throws ImageSourceException {
        return delegate.crop(x, y, width, height, scale);
    }

    @Override
    public Map<String, Object> getMetadata() {
        return delegate.getMetadata();
    }

    @Override
    public void setOptions(Map<String, String> options) {
        if (options != null && options.containsKey("runtime")) {
            this.engine = options.get("runtime");
        }
    }

    // ── GainMapSource ─────────────────────────────────────────────────────────

    @Override
    public GainMapData getGainMap() throws ImageSourceException {
        ensureSplit();
        return gainMap;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Splits the source once: primary through the delegate, gain map data via
     * the WASM codec.  Sources without a gain map degrade to the delegate
     * behaviour.
     */
    private synchronized void ensureSplit() throws ImageSourceException {
        if (splitDone) {
            return;
        }

        GainMapCodec codec;
        try {
            codec = GainMapCodec.shared(engine);
        } catch (IOException e) {
            throw new ImageSourceException("Cannot initialise the UltraHDR codec: " + e.getMessage(), e);
        }
        try {
            GainMapCodec.UhdrSplit split;
            try {
                split = codec.decode(sourceBytes);
            } catch (UltraHdrException e) {
                log.debug("Source is not an UltraHDR image ({}); falling back to plain JPEG",
                          e.getMessage());
                loadDelegate(sourceBytes);
                splitDone = true;
                return;
            }

            // Decode the primary image through the delegate (regular ImageIO path).
            loadDelegate(split.primaryJpeg());
            splitDone = true;

            BufferedImage gainmapImage = ImageIO.read(new ByteArrayInputStream(split.gainmapJpeg()));
            if (gainmapImage == null) {
                throw new ImageSourceException("Gain map image of " + url + " could not be decoded");
            }

            gainMap = new GainMapData(split.gainmapJpeg(), split.metadataJson(),
                                      gainmapImage.getWidth(), gainmapImage.getHeight());
        } catch (IOException e) {
            throw new ImageSourceException("Cannot split UltraHDR source " + url, e);
        }
    }

    /**
     * Feeds raw JPEG bytes to the delegate via an in-memory URL.
     */
    private void loadDelegate(byte[] jpeg) throws ImageSourceException {
        URLStreamHandler handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL u) {
                return new URLConnection(u) {
                    @Override
                    public InputStream getInputStream() {
                        return new ByteArrayInputStream(jpeg);
                    }

                    @Override
                    public void connect() {
                        // Nothing to do — the stream is served from memory.
                    }
                };
            }
        };
        try {
            delegate.load(new URL(null, "memory:ultrahdr-primary-" + SEQUENCE.incrementAndGet(), handler));
        } catch (IOException e) {
            throw new ImageSourceException("Cannot load primary image of " + url, e);
        }
    }
}
