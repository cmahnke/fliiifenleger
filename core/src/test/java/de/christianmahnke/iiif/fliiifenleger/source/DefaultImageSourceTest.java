package de.christianmahnke.iiif.fliiifenleger.source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DefaultImageSourceTest {

    private URL validImageUrl;
    private URL nonExistentImageUrl;

    @BeforeEach
    public void setUp() throws MalformedURLException {
        File validImageFile = new File("src/test/resources/images/page011.jpg");
        assertTrue(validImageFile.exists(), "Test image file must exist");
        validImageUrl = validImageFile.toURI().toURL();

        File nonExistentImageFile = new File("src/test/resources/images/nonexistent.jpg");
        nonExistentImageUrl = nonExistentImageFile.toURI().toURL();
    }

    @Test
    public void constructor_shouldLoadImageSuccessfully() throws ImageSourceException {
        DefaultImageSource source = new DefaultImageSource(validImageUrl);
        assertNotNull(source.getImage(), "Image should not be null");
        assertEquals(validImageUrl, source.getUrl(), "URL should match");
        assertEquals(4615, source.getWidth(), "Width should be correct");
        assertEquals(3440, source.getHeight(), "Height should be correct");
    }

    @Test
    public void constructor_shouldThrowExceptionForNonExistentImage() {
        assertThrows(ImageSourceException.class, () -> new DefaultImageSource(nonExistentImageUrl));
    }

    @Test
    public void getName_shouldReturnDefault() throws ImageSourceException {
        DefaultImageSource source = new DefaultImageSource(validImageUrl);
        assertEquals("default", source.getName(), "Name should be 'default'");
    }

    @Test
    public void crop_shouldCropWithoutScaling() throws ImageSourceException {
        DefaultImageSource source = new DefaultImageSource(validImageUrl);
        int x = 10, y = 20, width = 100, height = 150;

        BufferedImage cropped = source.crop(x, y, width, height, 1.0);

        assertNotNull(cropped);
        assertEquals(width, cropped.getWidth(), "Cropped width should be correct");
        assertEquals(height, cropped.getHeight(), "Cropped height should be correct");
    }

    @Test
    public void crop_shouldCropAndScale() throws ImageSourceException {
        DefaultImageSource source = new DefaultImageSource(validImageUrl);
        int x = 10, y = 20, width = 100, height = 150;
        double scale = 2.0;

        BufferedImage scaled = source.crop(x, y, width, height, scale);

        assertNotNull(scaled);
        assertEquals((int) Math.ceil(width / scale), scaled.getWidth(), "Scaled width should be correct");
        assertEquals((int) Math.ceil(height / scale), scaled.getHeight(), "Scaled height should be correct");
    }

    @Test
    public void crop_shouldThrowExceptionForInvalidRegion() throws ImageSourceException {
        DefaultImageSource source = new DefaultImageSource(validImageUrl);
        // Crop region is outside the image bounds
        assertThrows(ImageSourceException.class, () ->
                source.crop(0, 0, source.getWidth() + 1, source.getHeight() + 1, 1.0));
    }

    @Test
    public void getMetadata_shouldReturnNonEmptyMapForJpg() throws ImageSourceException {
        DefaultImageSource source = new DefaultImageSource(validImageUrl);
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

        DefaultImageSource source = new DefaultImageSource(tempFile.toURI().toURL());
        Map<String, Object> metadata = source.getMetadata();

        assertNotNull(metadata);
        // The metadata-extractor might still find some basic file properties
        // but we can assert it doesn't contain typical photo metadata like "Exif IFD0"
        assertFalse(metadata.containsKey("Exif IFD0"), "Should not contain EXIF data");
    }
}