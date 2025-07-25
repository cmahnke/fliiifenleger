package de.christianmahnke.iiif.fliiifenleger;

import de.christianmahnke.iiif.fliiifenleger.sink.DefaultTileSink;
import de.christianmahnke.iiif.fliiifenleger.source.DefaultImageSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSourceException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TilerTest {

    @TempDir
    Path tempDir;

    private Tiler tiler;
    private ImageSource imageSource;

    @BeforeEach
    public void setUp() throws IOException, ImageSourceException {
        tiler = new Tiler();

        File validImageFile = new File("src/test/resources/images/page011.jpg");
        assertTrue(validImageFile.exists(), "Test image file must exist");
        URL validImageUrl = validImageFile.toURI().toURL();
        imageSource = new DefaultImageSource(validImageUrl);
    }

    @Test
    public void testLoadSourcesAndSinks() {
        assertFalse(Tiler.SOURCE_REGISTRY.isEmpty(), "Source registry should not be empty");
        assertTrue(Tiler.SOURCE_REGISTRY.containsKey("default"), "Source registry should contain 'default'");
        assertTrue(Tiler.SOURCE_REGISTRY.containsKey("jxl"), "Source registry should contain 'jxl'");

        assertFalse(Tiler.SINK_REGISTRY.isEmpty(), "Sink registry should not be empty");
        assertTrue(Tiler.SINK_REGISTRY.containsKey("default"), "Sink registry should contain 'default'");
    }

    @Test
    public void testCreateImageV2() throws Exception {
        int tileSize = 512;
        int zoomLevels = 4;
        String identifier = "http://localhost/iiif/";
        ImageInfo.IIIFVersion version = ImageInfo.IIIFVersion.V2;

        ImageInfo imageInfo = new ImageInfo(imageSource, tileSize, tileSize, zoomLevels, identifier, version);
        DefaultTileSink sink = new DefaultTileSink();

        Path imageOutputDir = tiler.createImage(imageInfo, tempDir, version, sink);

        // Verify info.json was created
        Path infoJsonPath = imageOutputDir.resolve("info.json");
        assertTrue(Files.exists(infoJsonPath), "info.json should exist");

        // Verify some tiles were created
        // e.g., full size tile
        Path fullTilePath = imageOutputDir.resolve("full/full/0/default.jpg");
        assertTrue(Files.exists(fullTilePath), "Full size tile should exist");

        // e.g., a specific tile from a scaled level
        Path scaledTilePath = imageOutputDir.resolve("1024,1024,512,512/512/0/default.jpg");
        assertTrue(Files.exists(scaledTilePath), "A scaled tile should exist");
    }

    @Test
    public void testCreateImageV3() throws Exception {
        int tileSize = 1024;
        int zoomLevels = 5;
        String identifier = "http://localhost/iiif/";
        ImageInfo.IIIFVersion version = ImageInfo.IIIFVersion.V3;

        ImageInfo imageInfo = new ImageInfo(imageSource, tileSize, tileSize, zoomLevels, identifier, version);
        DefaultTileSink sink = new DefaultTileSink();

        Path imageOutputDir = tiler.createImage(imageInfo, tempDir, version, sink);

        // Verify info.json was created
        Path infoJsonPath = imageOutputDir.resolve("info.json");
        assertTrue(Files.exists(infoJsonPath), "info.json should exist");

        // Verify some tiles were created for V3
        // e.g., max size tile
        Path maxTilePath = imageOutputDir.resolve("full/max/0/default.jpg");
        assertTrue(Files.exists(maxTilePath), "Max size tile should exist for V3");

        // e.g., a specific tile from a scaled level
        Path scaledTilePath = imageOutputDir.resolve("2048,1024,1024,1024/1024,1024/0/default.jpg");
        assertTrue(Files.exists(scaledTilePath), "A scaled tile should exist for V3");
    }
}
