package de.christianmahnke.iiif.fliiifenleger.source;

import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.RasterFormatException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import com.google.auto.service.AutoService;
import lombok.NoArgsConstructor;

@NoArgsConstructor(force = true)
@AutoService(ImageSource.class)
public class JxlImageSource extends AbstractImageSource implements ImageSource {
    private final BufferedImage image;
    private final URL url;
    private static final String NAME = "jxl";

    public JxlImageSource(URL url) throws ImageSourceException {
        try {
            this.url = url;
            BufferedImage loadedImage = ImageIO.read(AbstractImageSource.getInputStream(this.url));
            if (loadedImage == null) {
                throw new ImageSourceException("Could not read JXL image file (is the imageio-jxl plugin on the classpath?): " + url);
            }
            this.image = removeAlphaChannelIfPresent(loadedImage);
        } catch (MalformedURLException e) {
            throw new ImageSourceException("Could not create URL from path: " +url, e);
        } catch (IOException e) {
            throw new ImageSourceException("Could not read image from path: " + url, e);
        } catch (Exception e) {
            throw new ImageSourceException("Could not read JXL image from path: " + url, e);
        }
    }

    private BufferedImage removeAlphaChannelIfPresent(BufferedImage original) {
        if (!original.getColorModel().hasAlpha()) {
            return original;
        }

        BufferedImage newImage = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = newImage.createGraphics();
        try {
            g.drawImage(original, 0, 0, null);
        } finally {
            g.dispose();
        }
        return newImage;
    }

    @Override
    public BufferedImage getImage() {
        return image;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public int getWidth() {
        return image.getWidth();
    }

    @Override
    public int getHeight() {
        return image.getHeight();
    }

    @Override
    public BufferedImage crop(int x, int y, int width, int height, double scale) throws ImageSourceException {
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

        BufferedImage scaled = new BufferedImage(newWidth, newHeight, cropped.getType());
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
        // The underlying JXL library doesn't expose EXIF/XMP metadata easily.
        // We'll return some basic decoder info, similar to the Rust version.
        return Map.of(
            "jxl_decoder", "imageio-jxl"
        );
    }

    public String getName() {
        return JxlImageSource.NAME;
    }

}