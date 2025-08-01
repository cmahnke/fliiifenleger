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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FilterImageSourceTest {

    private ImageSource sourceImage;

    @BeforeEach
    void setUp() throws IOException, ImageSourceException {
        System.setProperty("java.awt.headless", "true");
        File validImageFile = new File("src/test/resources/images/page011.jpg");
        URL validImageUrl = validImageFile.toURI().toURL();
        sourceImage = new DefaultImageSource();
        sourceImage.load(validImageUrl);
    }

    @ParameterizedTest
    @ValueSource(strings = {"grayscale", "invert", "sepia", "none"})
    void testSimpleFilters(String filterType) throws ImageSourceException {
        FilterImageSource filterImageSource = new FilterImageSource();
        Map<String, String> options = new HashMap<>();
        options.put("type", filterType);
        filterImageSource.setOptions(options);
        filterImageSource.load(sourceImage);

        BufferedImage filteredImage = filterImageSource.getImage();
        assertNotNull(filteredImage);
        assertEquals(sourceImage.getWidth(), filteredImage.getWidth());
        assertEquals(sourceImage.getHeight(), filteredImage.getHeight());
    }

    @Test
    void testBlurFilter() throws ImageSourceException {
        FilterImageSource filterImageSource = new FilterImageSource();
        Map<String, String> options = new HashMap<>();
        options.put("type", "blur");
        options.put("blurRadius", "5");
        filterImageSource.setOptions(options);
        filterImageSource.load(sourceImage);

        BufferedImage filteredImage = filterImageSource.getImage();
        assertNotNull(filteredImage);
    }

    @Test
    void testPosterizeFilter() throws ImageSourceException {
        FilterImageSource filterImageSource = new FilterImageSource();
        Map<String, String> options = new HashMap<>();
        options.put("type", "posterize");
        options.put("posterizeLevels", "8");
        filterImageSource.setOptions(options);
        filterImageSource.load(sourceImage);

        BufferedImage filteredImage = filterImageSource.getImage();
        assertNotNull(filteredImage);
    }

    @Test
    void testThresholdFilter() throws ImageSourceException {
        FilterImageSource filterImageSource = new FilterImageSource();
        Map<String, String> options = new HashMap<>();
        options.put("type", "threshold");
        options.put("thresholdValue", "100");
        filterImageSource.setOptions(options);
        filterImageSource.load(sourceImage);

        BufferedImage filteredImage = filterImageSource.getImage();
        assertNotNull(filteredImage);
    }

    @Test
    void testInvalidFilterType() {
        FilterImageSource filterImageSource = new FilterImageSource();
        Map<String, String> options = new HashMap<>();
        options.put("type", "nonexistent-filter");
        filterImageSource.setOptions(options);
        filterImageSource.load(sourceImage);

        Exception exception = assertThrows(IllegalArgumentException.class, filterImageSource::getImage);
        assertTrue(exception.getMessage().contains("Unknown filter type"));
    }
}