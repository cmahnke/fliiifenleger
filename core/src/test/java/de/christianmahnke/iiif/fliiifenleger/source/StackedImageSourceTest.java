/**
 * Fliiifenleger
 * Copyright (C) 2025  Christian Mahnke
 *
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

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.Tiler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StackedImageSourceTest {

    private StackedImageSource stackedSource;
    private static String image1Path;
    private static DefaultImageSource source1;

    @TempDir
    static Path tempDir;

    /**
     * A mock ManipulatorImageSource for testing the chaining functionality.
     * It simply delegates all calls to the base source, but we can verify it was loaded.
     */
    @AutoService(ImageSource.class)
    public static class TestManipulator extends AbstractImageSource implements ManipulatorImageSource {
        private ImageSource baseSource;
        private boolean loaded = false;

        @Override
        public String getName() { return "test-manipulator"; }

        @Override
        public void load(ImageSource baseSource) {
            this.baseSource = baseSource;
            this.loaded = true;
        }

        public boolean isLoaded() { return loaded; }

        // Delegate methods
        @Override public BufferedImage getImage() throws ImageSourceException { return baseSource.getImage(); }
        @Override public URL getUrl() { return baseSource.getUrl(); }
        @Override public void load(URL url) { /* no-op for manipulator */ }
        @Override public int getWidth() { return baseSource.getWidth(); }
        @Override public int getHeight() { return baseSource.getHeight(); }
        @Override public BufferedImage crop(int x, int y, int width, int height, double scale) throws ImageSourceException { return baseSource.crop(x, y, width, height, scale); }
        @Override public Map<String, Object> getMetadata() { return baseSource.getMetadata(); }
    }

    @BeforeAll
    public static void setUpClass() throws Exception {
        // Manually register sources for testing purposes
        // In a real run, ServiceLoader would handle this.
        if (Tiler.SOURCE_REGISTRY.get("default") == null) {
            Tiler.SOURCE_REGISTRY.put("default", new DefaultImageSource());
        }
        if (Tiler.SOURCE_REGISTRY.get("test-manipulator") == null) {
            Tiler.SOURCE_REGISTRY.put("test-manipulator", new TestManipulator());
        }
        if (Tiler.SOURCE_REGISTRY.get("filter") == null) {
            Tiler.SOURCE_REGISTRY.put("filter", new FilterImageSource());
        }

        File image1File = new File("src/test/resources/images/page011.jpg"); // 4615x3440
        assertTrue(image1File.exists(), "Test image 1 must exist");
        image1Path = image1File.getAbsolutePath();

        source1 = new DefaultImageSource();
        source1.load(image1File.toURI().toURL());
    }

    @BeforeEach
    public void setUp() {
        stackedSource = new StackedImageSource();
    }
    
    @Test
    public void setOptions_legacy_shouldChainSources() {
        Map<String, String> options = Map.of(
                "source.0", "default:" + image1Path,
                "source.1", "test-manipulator"
        );

        stackedSource.setOptions(options);

        // Check dimensions are from the base source
        assertEquals(source1.getWidth(), stackedSource.getWidth());
        assertEquals(source1.getHeight(), stackedSource.getHeight());
        assertNotNull(stackedSource.getUrl());
        assertTrue(stackedSource.getUrl().getPath().endsWith("stacked-image.composite"));

        // Verify the final source is the manipulator and it has loaded the base
        assertInstanceOf(TestManipulator.class, stackedSource.getFinalSource());
        assertTrue(((TestManipulator) stackedSource.getFinalSource()).isLoaded());
    }

    @Test
    public void setOptions_yaml_shouldChainSources() throws IOException {
        String yamlContent = String.format(
            "sources:\n" +
            "  - type: default\n" +
            "    path: %s\n" +
            "  - type: test-manipulator\n",
            image1Path
        );
        Path configFile = tempDir.resolve("config.yaml");
        Files.write(configFile, yamlContent.getBytes());

        Map<String, String> options = Map.of("config", configFile.toString());
        stackedSource.setOptions(options);

        assertEquals(source1.getWidth(), stackedSource.getWidth());
        assertEquals(source1.getHeight(), stackedSource.getHeight());
        assertNotNull(stackedSource.getUrl());

        assertInstanceOf(TestManipulator.class, stackedSource.getFinalSource());
        assertTrue(((TestManipulator) stackedSource.getFinalSource()).isLoaded());
    }

    @Test
    public void setOptions_yaml_shouldChainWithFilterSource() throws IOException {
        String yamlContent = String.format(
            "sources:\n" +
            "  - type: default\n" +
            "    path: %s\n" +
            "  - type: filter\n" +
            "    options:\n" +
            "      type: grayscale\n",
            image1Path
        );
        Path configFile = tempDir.resolve("config.yaml");
        Files.write(configFile, yamlContent.getBytes());

        Map<String, String> options = Map.of("config", configFile.toString());
        stackedSource.setOptions(options);

        assertEquals(source1.getWidth(), stackedSource.getWidth());
        assertInstanceOf(FilterImageSource.class, stackedSource.getFinalSource());
    }

    @Test
    public void setOptions_shouldThrowExceptionForMissingOptions() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            stackedSource.setOptions(Map.of());
        });
        assertTrue(ex.getMessage().contains("requires 'source.N' options"));
    }

    @Test
    public void setOptions_shouldThrowExceptionForInvalidSourceType() {
        Map<String, String> options = Map.of("source.0", "nonexistent:" + image1Path);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            stackedSource.setOptions(options);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Unknown source type"));
    }

    @Test
    public void setOptions_shouldThrowExceptionForNonManipulatorInChain() {
        // The second source is 'default', which is not a ManipulatorImageSource
        Map<String, String> options = Map.of(
                "source.0", "default:" + image1Path,
                "source.1", "default" // Using a non-manipulator type
        );
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            stackedSource.setOptions(options);
        });
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("is not a ManipulatorImageSource"));
    }

    @Test
    public void crop_shouldDelegateToFinalSource() throws ImageSourceException {
        Map<String, String> options = Map.of("source.0", "default:" + image1Path);
        stackedSource.setOptions(options);

        double scale = 2.0;
        int cropWidth = 200;
        int cropHeight = 150;

        BufferedImage cropped = stackedSource.crop(10, 10, cropWidth, cropHeight, scale);
        BufferedImage expected = source1.crop(10, 10, cropWidth, cropHeight, scale);

        assertNotNull(cropped);
        assertEquals(expected.getWidth(), cropped.getWidth());
        assertEquals(expected.getHeight(), cropped.getHeight());
    }
}
