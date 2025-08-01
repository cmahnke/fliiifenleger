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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DefaultImageSourceTest {

    private URL validImageUrl;
    private URL nonExistentImageUrl;

    @BeforeAll
    public static void setUpClass() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    public void setUp() throws MalformedURLException {
        File validImageFile = new File("src/test/resources/images/page011.jpg");
        assertTrue(validImageFile.exists(), "Test image file must exist");
        validImageUrl = validImageFile.toURI().toURL();

        File nonExistentImageFile = new File("src/test/resources/images/nonexistent.jpg");
        nonExistentImageUrl = nonExistentImageFile.toURI().toURL();
    }

    @Test
    public void graphicsEnvironment_shouldBeHeadless() {
        assertTrue(GraphicsEnvironment.isHeadless(), "Should be executed headless");
    }

    @Test
    public void constructor_shouldLoadImageSuccessfully() throws Exception {
        DefaultImageSource source = new DefaultImageSource();
        source.load(validImageUrl);
        assertNotNull(source.getImage(), "Image should not be null");
        assertEquals(validImageUrl, source.getUrl(), "URL should match");
        assertEquals(4615, source.getWidth(), "Width should be correct");
        assertEquals(3440, source.getHeight(), "Height should be correct");
    }

    @Test
    public void constructor_shouldThrowExceptionForNonExistentImage() {
        DefaultImageSource source = new DefaultImageSource();
        // The exception is thrown during image loading, which is triggered by getImage() or crop()
        assertThrows(ImageSourceException.class, () -> {
            source.load(nonExistentImageUrl);
        });
    }

    @Test
    public void setUrl_shouldLoadImage() throws Exception {
        DefaultImageSource source = new DefaultImageSource();
        source.load(validImageUrl);
        source.getImage(); // Trigger loading
        assertNotNull(source.getImage(), "Image should be loaded after setUrl");
        assertEquals(4615, source.getWidth(), "Width should be correct after setUrl");
    }

    @Test
    public void getName_shouldReturnDefault() throws Exception {
        DefaultImageSource source = new DefaultImageSource();
        source.load(validImageUrl);
        assertEquals("default", source.getName(), "Name should be 'default'");
    }

    @Test
    public void crop_shouldCropWithoutScaling() throws Exception {
        DefaultImageSource source = new DefaultImageSource();
        source.load(validImageUrl);
        int x = 10, y = 20, width = 100, height = 150;

        BufferedImage cropped = source.crop(x, y, width, height, 1.0);

        assertNotNull(cropped);
        assertEquals(width, cropped.getWidth(), "Cropped width should be correct");
        assertEquals(height, cropped.getHeight(), "Cropped height should be correct");
    }

    @Test
    public void crop_shouldCropAndScale() throws Exception {
        DefaultImageSource source = new DefaultImageSource();
        source.load(validImageUrl);
        int x = 10, y = 20, width = 100, height = 150;
        double scale = 2.0;

        BufferedImage scaled = source.crop(x, y, width, height, scale);

        assertNotNull(scaled);
        assertEquals((int) Math.ceil(width / scale), scaled.getWidth(), "Scaled width should be correct");
        assertEquals((int) Math.ceil(height / scale), scaled.getHeight(), "Scaled height should be correct");
    }

    @Test
    public void crop_shouldThrowExceptionForInvalidRegion() throws Exception {
        DefaultImageSource source = new DefaultImageSource();
        source.load(validImageUrl);
        // Crop region is outside the image bounds
        assertThrows(ImageSourceException.class, () ->
                source.crop(0, 0, source.getWidth() + 1, source.getHeight() + 1, 1.0));
    }

    @Test
    public void getMetadata_shouldReturnNonEmptyMapForJpg() throws Exception {
        DefaultImageSource source = new DefaultImageSource();
        source.load(validImageUrl);
        Map<String, Object> metadata = source.getMetadata();

        assertNotNull(metadata);
        assertFalse(metadata.isEmpty(), "Metadata map should not be empty");
        assertTrue(metadata.containsKey("JPEG"), "Should contain JPEG metadata directory");
    }

    @Test
    public void getMetadata_shouldHandleImagesWithoutMetadata() throws Exception {
        // Create a simple blank image that has no metadata
        BufferedImage blankImage = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        File tempFile = File.createTempFile("blank", ".jpg");
        javax.imageio.ImageIO.write(blankImage, "jpg", tempFile);
        tempFile.deleteOnExit();

        DefaultImageSource source = new DefaultImageSource();
        source.load(tempFile.toURI().toURL());
        Map<String, Object> metadata = source.getMetadata();

        assertNotNull(metadata);
        // The metadata-extractor might still find some basic file properties
        // but we can assert it doesn't contain typical photo metadata like "Exif IFD0"
        assertFalse(metadata.containsKey("Exif IFD0"), "Should not contain EXIF data");
    }
}