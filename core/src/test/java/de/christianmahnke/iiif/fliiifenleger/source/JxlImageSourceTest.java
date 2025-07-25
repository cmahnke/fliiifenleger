package de.christianmahnke.iiif.fliiifenleger.source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.awt.image.BufferedImage;
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
    public void constructor_shouldLoadImageSuccessfully() throws ImageSourceException {
        JxlImageSource source = new JxlImageSource(validJxlUrl);
        assertNotNull(source.getImage(), "Image should not be null");
        assertEquals(validJxlUrl, source.getUrl(), "URL should match");
        assertTrue(source.getWidth() > 0, "Width should be greater than 0");
        assertTrue(source.getHeight() > 0, "Height should be greater than 0");
    }

    @Test
    public void constructor_shouldThrowExceptionForNonExistentImage() throws ImageSourceException {
        assertThrows(ImageSourceException.class, () -> new JxlImageSource(nonExistentJxlUrl));
    }

    @Test
    public void getName_shouldReturnJxl() throws ImageSourceException {
        JxlImageSource source = new JxlImageSource(validJxlUrl);
        assertEquals("jxl", source.getName(), "Name should be 'jxl'");
    }

    @Test
    public void crop_shouldCropAndScale() throws ImageSourceException {
        JxlImageSource source = new JxlImageSource(validJxlUrl);
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
        JxlImageSource source = new JxlImageSource(validJxlUrl);
        Map<String, Object> metadata = source.getMetadata();

        assertNotNull(metadata);
        assertTrue(metadata.containsKey("jxl_decoder"), "Metadata should contain jxl_decoder key");
        assertEquals("imageio-jxl", metadata.get("jxl_decoder"), "Decoder should be imageio-jxl");
    }
}
