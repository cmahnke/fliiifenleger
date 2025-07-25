package de.christianmahnke.iiif.fliiifenleger.source;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.RasterFormatException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(ImageSource.class)
@NoArgsConstructor(force = true)
public class DefaultImageSource extends AbstractImageSource implements ImageSource {
    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final BufferedImage image;
    private final URL url;
    private static final String NAME = "default";

    public DefaultImageSource(URL url) throws ImageSourceException {
        try {
            this.url = url;
            log.debug("Loading image from: {}", url);
            BufferedImage loadedImage = ImageIO.read(AbstractImageSource.getInputStream(this.url));
            if (loadedImage == null) {
                throw new ImageSourceException("Could not read image file (unsupported format or file is corrupt): " + url);
            }
            this.image = loadedImage;
        } catch (MalformedURLException e) {
            throw new ImageSourceException("Could not create URL from path: " + url, e);
        } catch (Exception e) {
            throw new ImageSourceException("Could not read image from path: " + url, e);
        }
    }

    public String getName() {
        return DefaultImageSource.NAME;
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
            // Use a high-quality scaling algorithm, similar to Lanczos3
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(cropped, 0, 0, newWidth, newHeight, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    @Override
    public Map<String, Object> getMetadata() {
        Map<String, Object> allMetadata = new HashMap<>();
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(AbstractImageSource.getInputStream(this.url));
            for (Directory directory : metadata.getDirectories()) {
                Map<String, String> tags = new HashMap<>();
                for (Tag tag : directory.getTags()) {
                    tags.put(tag.getTagName(), tag.getDescription());
                }
                allMetadata.put(directory.getName(), tags);
            }
        } catch (ImageProcessingException | ImageSourceException | IOException e) {
            // Log the error or handle it as needed. For now, we'''ll return an empty map.
            // In a real application, you might want to use a logging framework.
            log.warn("Warning: Could not read metadata for {}. {}", this.url, e.getMessage());
        }
        return allMetadata;
    }

}