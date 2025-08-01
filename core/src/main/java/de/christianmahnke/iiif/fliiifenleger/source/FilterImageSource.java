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
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.awt.image.RescaleOp;
import java.util.Map;

@AutoService(ImageSource.class)
@NoArgsConstructor
public class FilterImageSource extends AbstractManipulatorImageSource {
    private static final String NAME = "filter";
    private String filterType = "none";
    private int thresholdValue = 128; // Default for threshold filter
    private int posterizeLevels = 4; // Default for posterize filter
    private int blurRadius = 3; // Default for blur filter

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
        if (options.containsKey("blurRadius")) {
            this.blurRadius = Integer.parseInt(options.get("blurRadius"));
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
            case "blur":
                return blur(original);
            case "none":
                return original; // No filter
            default:
                throw new IllegalArgumentException("Unknown filter type: " + filterType);
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
        if (posterizeLevels <= 1) {
            return original; // No change or invalid level
        }

        BufferedImage posterizedImage = new BufferedImage(original.getWidth(), original.getHeight(), original.getType());
        // The number of segments to divide the color range into.
        int numLevels = Math.max(2, posterizeLevels);
        // The size of each color segment.
        int step = 255 / (numLevels - 1);

        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                int rgb = original.getRGB(x, y);
                int a = (rgb >> 24) & 0xFF;
                int r = ((rgb >> 16) & 0xFF) / step * step;
                int g = ((rgb >> 8) & 0xFF) / step * step;
                int b = (rgb & 0xFF) / step * step;

                int newRgb = (a << 24) | (r << 16) | (g << 8) | b;
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

    private BufferedImage blur(BufferedImage original) {
        // The radius must be odd for a symmetrical kernel
        int radius = (blurRadius % 2 == 0) ? blurRadius + 1 : blurRadius;
        int size = radius * radius;
        float[] data = new float[size];
        for (int i = 0; i < size; i++) {
            data[i] = 1.0f / size;
        }

        Kernel kernel = new Kernel(radius, radius, data);

        // ConvolveOp requires the source and destination to have compatible ColorModels.
        // Creating a destination image of the same type ensures this.
        BufferedImage blurredImage = new BufferedImage(original.getWidth(), original.getHeight(), original.getType());

        // EDGE_NO_OP is a good default to avoid darkening at the edges.
        ConvolveOp op = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
        op.filter(original, blurredImage);

        return blurredImage;
    }
}