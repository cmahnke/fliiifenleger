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

@NoArgsConstructor
@AutoService(ImageSource.class)
public class JxlImageSource extends AbstractImageSource implements ImageSource {
    private BufferedImage image;
    private static final String NAME = "jxl";

    @Override
    public BufferedImage getImage() {
        return image;
    }

    @Override
    public void load(URL url)throws ImageSourceException{
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

     private void loadImage() throws ImageSourceException  {
        if (this.url == null){
            throw new IllegalStateException("URL has not been set for JxlImageSource.");
        }
        try {
            BufferedImage loadedImage = ImageIO.read(AbstractImageSource.getInputStream(this.url));
            if (loadedImage == null) {
                throw new ImageSourceException("Could not read JXL image file (is the imageio-jxl plugin on the classpath?): " + url);
            }
            this.image = loadedImage;
        } catch (MalformedURLException e) {
            throw new ImageSourceException("Could not create URL from path: " +url, e);
        } catch (IOException e) {
            throw new ImageSourceException("Could not read image from path: " + url, e);
        } catch (Exception e) {
            throw new ImageSourceException("Could not read JXL image from path: " + url, e);
        }

    }

    @Override
    public BufferedImage crop(int x, int y, int width, int height, double scale) throws ImageSourceException {
        getImage();
        if (image == null) loadImage();
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