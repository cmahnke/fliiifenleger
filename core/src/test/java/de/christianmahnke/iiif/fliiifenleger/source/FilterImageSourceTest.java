// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
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