/**
 * Fliiifenleger
 * Copyright (C) 2025  Christian Mahnke
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.christianmahnke.iiif.fliiifenleger.source;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.google.auto.service.AutoService;

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
@NoArgsConstructor
public class DefaultImageSource extends AbstractImageSource implements ImageSource {
    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private BufferedImage image;
    private static final String NAME = "default";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public BufferedImage getImage()throws ImageSourceException {
 if (image == null) {
            loadImage();
        }
        return image;
    }

    @Override
    public void load(URL url)throws ImageSourceException{
        this.url = url;
        loadImage();
    }

    private void loadImage()throws ImageSourceException  {
        if (this.url == null){
             throw new IllegalStateException("URL has not been set for DefaultImageSource.");
        }
        try {
            log.debug("Loading image from: {}", url);
            BufferedImage loadedImage = ImageIO.read(AbstractImageSource.getInputStream(this.url));
            if (loadedImage == null) {
                throw new ImageSourceException("Could not read image file (unsupported format or file is corrupt): " + url);
            }
            this.image = loadedImage;
        } catch (MalformedURLException e) {
            throw new ImageSourceException("Could not create URL from path: " + url, e);
         } catch (IOException e) {
            throw new ImageSourceException("Could not read image from path: " + url, e);
        }
            
    }

    @Override
    public int getWidth() {
                 if (image == null) {
            throw new IllegalStateException("ImageSource not initilized.");
        }
        return image.getWidth();
    }

    @Override
    public int getHeight() {
                 if (image == null) {
            throw new IllegalStateException("ImageSource not initilized.");
        }
        return image.getHeight();
    }

    @Override
    public BufferedImage crop(int x, int y, int width, int height, double scale) throws ImageSourceException {
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