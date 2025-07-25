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
    public void constructor_shouldLoadInfoJsonSuccessfully() throws ImageSourceException {
        IiifImageSource source = new IiifImageSource(iiifInfoJsonUrl);
        assertNotNull(source, "Source should be created");
        assertEquals(iiifInfoJsonUrl, source.getUrl());
        assertEquals(1168, source.getWidth(), "Width should be parsed from info.json");
        assertEquals(1488, source.getHeight(), "Height should be parsed from info.json");
    }

    @Test
    public void constructor_shouldThrowExceptionForInvalidUrl() throws MalformedURLException {
        URL badUrl = new URL("http://localhost/non-existent/info.json");
        assertThrows(ImageSourceException.class, () -> new IiifImageSource(badUrl));
    }

    @Test
    public void getName_shouldReturnIiif() throws ImageSourceException {
        IiifImageSource source = new IiifImageSource(iiifInfoJsonUrl);
        assertEquals("iiif", source.getName());
    }

    @Test
    public void crop_shouldFetchRegionWithoutScaling() throws ImageSourceException {
        IiifImageSource source = new IiifImageSource(iiifInfoJsonUrl);
        int x = 100, y = 100, width = 200, height = 300;
        BufferedImage cropped = source.crop(x, y, width, height, 1.0);

        assertNotNull(cropped);
        assertEquals(width, cropped.getWidth());
        assertEquals(height, cropped.getHeight());
    }

    @Test
    public void crop_shouldFetchRegionWithScaling() throws ImageSourceException {
        IiifImageSource source = new IiifImageSource(iiifInfoJsonUrl);
        int x = 100, y = 100, width = 400, height = 600;
        double scale = 2.0;
        BufferedImage scaled = source.crop(x, y, width, height, scale);

        assertNotNull(scaled);
        assertEquals((int) Math.ceil(width / scale), scaled.getWidth());
        assertEquals((int) Math.ceil(height / scale), scaled.getHeight());
    }

    @Test
    public void getMetadata_shouldReturnDataFromInfoJson() throws ImageSourceException {
        IiifImageSource source = new IiifImageSource(iiifInfoJsonUrl);
        Map<String, Object> metadata = source.getMetadata();
        assertNotNull(metadata);
        assertTrue(metadata.containsKey("iiif_source_id"));
        assertTrue(metadata.containsKey("iiif_profile"));
    }
}