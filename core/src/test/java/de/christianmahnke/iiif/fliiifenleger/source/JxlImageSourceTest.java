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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import java.awt.image.BufferedImage;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

@EnabledOnJre(JRE.JAVA_22)
public class JxlImageSourceTest {

    private URL validJxlUrl;
    private URL nonExistentJxlUrl;

    @BeforeAll
    public static void setUpClass() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    public void setUp() throws MalformedURLException {
        File validImageFile = new File("src/test/resources/images/front.jxl");
        // Skip tests if the test JXL file doesn't exist
        Assumptions.assumeTrue(validImageFile.exists(), "Test JXL image file must exist at src/test/resources/images/front.jxl");
        validJxlUrl = validImageFile.toURI().toURL();

        File nonExistentImageFile = new File("src/test/resources/images/nonexistent.jxl");
        nonExistentJxlUrl = nonExistentImageFile.toURI().toURL();
    }

    @Test
    public void graphicsEnvironment_shouldBeHeadless() {
        assertTrue(GraphicsEnvironment.isHeadless(), "Should be executed headless");
    }

    @Test
    public void constructor_shouldLoadImageSuccessfully() throws ImageSourceException {
        JxlImageSource source = new JxlImageSource();
        source.load(validJxlUrl);
        source.getImage(); // Trigger loading
        assertNotNull(source.getImage(), "Image should not be null");
        assertEquals(validJxlUrl, source.getUrl(), "URL should match");
        assertTrue(source.getWidth() > 0, "Width should be greater than 0");
        assertTrue(source.getHeight() > 0, "Height should be greater than 0");
    }

    @Test
    public void constructor_shouldThrowExceptionForNonExistentImage() throws ImageSourceException {
        JxlImageSource source = new JxlImageSource();
        assertThrows(ImageSourceException.class, () -> {
            source.load(nonExistentJxlUrl);
        });
    }

    @Test
    public void getName_shouldReturnJxl() throws ImageSourceException {
        JxlImageSource source = new JxlImageSource();
        source.load(validJxlUrl);
        source.getImage(); // Trigger loading
        assertEquals("jxl", source.getName(), "Name should be 'jxl'");
    }

    @Test
    public void crop_shouldCropAndScale() throws ImageSourceException {
        JxlImageSource source = new JxlImageSource();
        source.load(validJxlUrl);
        source.getImage(); // Trigger loading
        int x = 0, y = 0, width = 50, height = 50;
        double scale = 2.0;

        // Ensure crop dimensions are within image bounds
        Assumptions.assumeTrue(source.getWidth() >= width && source.getHeight() >= height);

        BufferedImage scaled = source.crop(x, y, width, height, scale);

        assertNotNull(scaled);
        assertEquals((int) Math.ceil(width / scale), scaled.getWidth(), "Scaled width should be correct");
        assertEquals((int) Math.ceil(height / scale), scaled.getHeight(), "Scaled height should be correct");
    }

    @Test
    public void getMetadata_shouldReturnJxlDecoderInfo() throws ImageSourceException {
        JxlImageSource source = new JxlImageSource();
        source.load(validJxlUrl);
        source.getImage(); // Trigger loading
        Map<String, Object> metadata = source.getMetadata();

        assertNotNull(metadata);
        assertTrue(metadata.containsKey("jxl_decoder"), "Metadata should contain jxl_decoder key");
        assertEquals("imageio-jxl", metadata.get("jxl_decoder"), "Decoder should be imageio-jxl");
    }
}
