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

import java.awt.image.BufferedImage;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class IiifImageSourceTest {

    private URL iiifInfoJsonUrl;

    @BeforeEach
    public void setUp() throws MalformedURLException {
        // Using a public, stable IIIF endpoint for testing.
        // This test requires an internet connection.
        iiifInfoJsonUrl = new URL("https://images.sub.uni-goettingen.de/iiif/image/gdz:PPN001458469:00000002/info.json");
    }

    @Test
    public void setUrl_shouldLoadInfoJsonSuccessfully() throws ImageSourceException {
        IiifImageSource source = new IiifImageSource();
        source.load(iiifInfoJsonUrl);
        assertNotNull(source, "Source should be created");
        assertEquals(iiifInfoJsonUrl, source.getUrl());
        assertEquals(1168, source.getWidth(), "Width should be parsed from info.json");
        assertEquals(1488, source.getHeight(), "Height should be parsed from info.json");
    }

    @Test
    public void setUrl_shouldThrowExceptionForInvalidUrl() throws MalformedURLException {
        URL badUrl = new URL("http://localhost/non-existent/info.json");
        IiifImageSource source = new IiifImageSource();
        assertThrows(ImageSourceException.class, () -> source.load(badUrl));
    }

    @Test
    public void getName_shouldReturnIiif() throws ImageSourceException {
        IiifImageSource source = new IiifImageSource();
        source.load(iiifInfoJsonUrl);
        assertEquals("iiif", source.getName());
    }

    @Test
    public void crop_shouldFetchRegionWithoutScaling() throws ImageSourceException {
        IiifImageSource source = new IiifImageSource();
        source.load(iiifInfoJsonUrl);

        int x = 100, y = 100, width = 200, height = 300;
        BufferedImage cropped = source.crop(x, y, width, height, 1.0);

        assertNotNull(cropped);
        // IIIF servers may return slightly different sizes, so we check for a range.
        assertTrue(Math.abs(width - cropped.getWidth()) <= 1, "Width should be close to requested");
        assertTrue(Math.abs(height - cropped.getHeight()) <= 1, "Height should be close to requested");
    }

    @Test
    public void crop_shouldFetchRegionWithScaling() throws ImageSourceException {
        IiifImageSource source = new IiifImageSource();
        source.load(iiifInfoJsonUrl);

        int x = 100, y = 100, width = 400, height = 600;
        double scale = 2.0;
        BufferedImage scaled = source.crop(x, y, width, height, scale);

        assertNotNull(scaled);
        int expectedWidth = (int) Math.ceil(width / scale);
        int expectedHeight = (int) Math.ceil(height / scale);
        assertTrue(Math.abs(expectedWidth - scaled.getWidth()) <= 1, "Scaled width should be close to expected");
        assertTrue(Math.abs(expectedHeight - scaled.getHeight()) <= 1, "Scaled height should be close to expected");
    }

    @Test
    public void getMetadata_shouldReturnDataFromInfoJson() throws ImageSourceException {
        IiifImageSource source = new IiifImageSource();
        source.load(iiifInfoJsonUrl);
        Map<String, Object> metadata = source.getMetadata();
        assertNotNull(metadata);
        assertTrue(metadata.containsKey("iiif_source_id"));
        assertTrue(metadata.containsKey("iiif_profile"));
    }
}