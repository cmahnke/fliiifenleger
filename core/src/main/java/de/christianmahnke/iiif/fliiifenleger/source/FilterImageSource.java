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

import com.google.auto.service.AutoService;
import lombok.NoArgsConstructor;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.RescaleOp;
import java.util.Map;

@AutoService(ImageSource.class)
@NoArgsConstructor
public class FilterImageSource extends AbstractManipulatorImageSource {
    private static final String NAME = "filter";
    private String filterType = "none";
    private int thresholdValue = 128; // Default for threshold filter
    private int posterizeLevels = 4; // Default for posterize filter

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void setOptions(Map<String, String> options) {
        if (options == null) return;
        this.filterType = options.getOrDefault("type", "none");
        if (options.containsKey("threshold")) {
            this.thresholdValue = Integer.parseInt(options.get("threshold"));
        }
        if (options.containsKey("posterizeLevels")) {
            this.posterizeLevels = Integer.parseInt(options.get("posterizeLevels"));
        }
    }

    @Override
    public BufferedImage crop(int x, int y, int width, int height, double scale) throws ImageSourceException {
        // First, get the cropped image from the base source
        BufferedImage originalCrop = baseSource.crop(x, y, width, height, scale);

        // Now, apply the configured filter
        return applyFilter(originalCrop);
    }

    private BufferedImage applyFilter(BufferedImage original) {
        if (original == null) return null;

        switch (filterType.toLowerCase()) {
            case "grayscale":
                return toGrayscale(original);
            case "invert":
                return invertColors(original);
            case "posterize":
                return posterize(original);
            case "threshold":
                return toThreshold(original);
            case "sepia":
                return toSepia(original);
            case "none":
            default:
                return original; // No filter
        }
    }

    private BufferedImage toGrayscale(BufferedImage original) {
        BufferedImage grayImage = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        ColorConvertOp op = new ColorConvertOp(original.getColorModel().getColorSpace(), grayImage.getColorModel().getColorSpace(), null);
        op.filter(original, grayImage);
        return grayImage;
    }

    private BufferedImage invertColors(BufferedImage original) {
        // Invert colors using RescaleOp
        RescaleOp op = new RescaleOp(-1.0f, 255f, null);
        return op.filter(original, null);
    }

    private BufferedImage posterize(BufferedImage original) {
        int mask = 0xFF << (8 - (int)(Math.log(posterizeLevels) / Math.log(2)));
        BufferedImage posterizedImage = new BufferedImage(original.getWidth(), original.getHeight(), original.getType());

        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                int rgb = original.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                r &= mask;
                g &= mask;
                b &= mask;

                int newRgb = (rgb & 0xFF000000) | (r << 16) | (g << 8) | b;
                posterizedImage.setRGB(x, y, newRgb);
            }
        }
        return posterizedImage;
    }

    private BufferedImage toThreshold(BufferedImage original) {
        BufferedImage thresholdImage = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                int rgb = original.getRGB(x, y);
                // A simple luminance calculation
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);

                if (gray > thresholdValue) {
                    thresholdImage.setRGB(x, y, 0xFFFFFF); // White
                } else {
                    thresholdImage.setRGB(x, y, 0x000000); // Black
                }
            }
        }
        return thresholdImage;
    }

    private BufferedImage toSepia(BufferedImage original) {
        BufferedImage sepiaImage = new BufferedImage(original.getWidth(), original.getHeight(), original.getType());

        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                int rgb = original.getRGB(x, y);
                int a = (rgb >> 24) & 0xFF;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                int newR = (int) (0.393 * r + 0.769 * g + 0.189 * b);
                int newG = (int) (0.349 * r + 0.686 * g + 0.168 * b);
                int newB = (int) (0.272 * r + 0.534 * g + 0.131 * b);

                // Clamp values to 0-255 range
                r = Math.min(255, newR);
                g = Math.min(255, newG);
                b = Math.min(255, newB);

                int newRgb = (a << 24) | (r << 16) | (g << 8) | b;
                sepiaImage.setRGB(x, y, newRgb);
            }
        }
        return sepiaImage;
    }
}