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

import java.awt.image.BufferedImage;
import java.util.Map;
import java.net.URL;

/**
 * A generic source for an image that can be tiled.
 * This is analogous to the `ImageSource` trait in the Rust version.
 */
public interface ImageSource {
    /**
     * @return The (short) name of a ImageSource.
     */
    String getName();

    /**
     * @return The full source image as a BufferedImage.
     */
    BufferedImage getImage() throws ImageSourceException;

    /**
     * @return The URL for the image source, which can be used as a unique identifier.
     */
    URL getUrl();

    /**
     * @return Loads the image from URL
     */
    void load(URL url)throws ImageSourceException;

    /**
     * @return The width of the source image.
     */
    int getWidth();

    /**
     * @return The height of the source image.
     */
    int getHeight();

    /**
     * Extracts a rectangular region from the image.
     * @param scale The factor by which the cropped image should be scaled down. A scale of 1 means no scaling.
     * @return A new BufferedImage representing the cropped region.
     */
    BufferedImage crop(int x, int y, int width, int height, double scale) throws ImageSourceException;

    /**
     * Returns metadata extracted from the image, such as EXIF or XMP data.
     * @return A map representing the image metadata.
     */
    Map<String, Object> getMetadata();

        /**
     * Sets options for this image source.
     *
     * @param options A map of key-value pairs.
     */
    default void setOptions(Map<String, String> options) {}
}