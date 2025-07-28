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

package de.christianmahnke.iiif.fliiifenleger.debug;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

@WireMockTest
class IiifImageReassemblerTest {

    private static final int IMAGE_WIDTH = 2;
    private static final int IMAGE_HEIGHT = 2;
    private static final int TILE_SIZE = 1;

    @TempDir
    Path tempDir;

 private WireMockServer server;

    private URL infoJsonUrl;

    @BeforeEach
    void setUp() throws IOException, URISyntaxException {
        server = new WireMockServer(options().dynamicPort());
        server.start();

        infoJsonUrl = new URI(server.baseUrl() + "/iiif/2/test-image/info.json").toURL();

        // 1. Stub the info.json response
        server.stubFor(get(urlEqualTo("/iiif/2/test-image/info.json"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(createTestInfoJson(server.baseUrl()))));

        // 2. Stub the tile responses
        // We'll create a 2x2 image from four 1x1 tiles, each with a different color.
        stubTile(server, "0,0,1,1", Color.RED);    // Top-left
        stubTile(server, "1,0,1,1", Color.GREEN);  // Top-right
        stubTile(server, "0,1,1,1", Color.BLUE);   // Bottom-left
        stubTile(server, "1,1,1,1", Color.YELLOW); // Bottom-right
    }

    private String createTestInfoJson(String baseUrl) {
        return "{\n" +
                "  \"@context\": \"http://iiif.io/api/image/2/context.json\",\n" +
                "  \"@id\": \"" + baseUrl + "/iiif/2/test-image\",\n" +
                "  \"protocol\": \"http://iiif.io/api/image\",\n" +
                "  \"width\": " + IMAGE_WIDTH + ",\n" +
                "  \"height\": " + IMAGE_HEIGHT + ",\n" +
                "  \"tiles\": [\n" +
                "    {\n" +
                "      \"width\": " + TILE_SIZE + ",\n" +
                "      \"height\": " + TILE_SIZE + ",\n" +
                "      \"scaleFactors\": [1]\n" +
                "    }\n" +
                "  ],\n" +
                "  \"profile\": [\"http://iiif.io/api/image/2/level2.json\"]\n" +
                "}";
    }

    private void stubTile(WireMockServer server, String region, Color color) throws IOException {
        byte[] tileBytes = createTileImage(color);
        server.stubFor(get(urlEqualTo("/iiif/2/test-image/" + region + "/full/0/default.jpg"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "image/jpeg")
                        .withBody(tileBytes)));
    }

    private byte[] createTileImage(Color color) throws IOException {
        BufferedImage tile = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_RGB);
        tile.setRGB(0, 0, color.getRGB());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(tile, "jpg", baos);
        return baos.toByteArray();
    }

    @Test
    void load_shouldParseInfoJsonCorrectly() {
        IiifImageReassembler reassembler = new IiifImageReassembler(infoJsonUrl);
        assertDoesNotThrow(reassembler::load);
    }

    @Disabled
    @Test
    void reassemble_shouldCreateCorrectImageFromTiles() throws IOException {
        IiifImageReassembler reassembler = new IiifImageReassembler(infoJsonUrl);
        reassembler.load();
        BufferedImage reassembledImage = reassembler.reassemble();

        assertNotNull(reassembledImage);
        assertEquals(IMAGE_WIDTH, reassembledImage.getWidth());
        assertEquals(IMAGE_HEIGHT, reassembledImage.getHeight());

        // Verify the color of each pixel to ensure tiles were placed correctly
        assertEquals(Color.RED.getRGB(), reassembledImage.getRGB(0, 0));
        assertEquals(Color.GREEN.getRGB(), reassembledImage.getRGB(1, 0));
        assertEquals(Color.BLUE.getRGB(), reassembledImage.getRGB(0, 1));
        assertEquals(Color.YELLOW.getRGB(), reassembledImage.getRGB(1, 1));
    }

    @Test
    void saveImage_shouldWriteFileToDisk() throws IOException {
        IiifImageReassembler reassembler = new IiifImageReassembler(infoJsonUrl);
        BufferedImage testImage = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Path outputPath = tempDir.resolve("output.jpg");

        reassembler.saveImage(testImage, outputPath, "jpg");

        assertTrue(outputPath.toFile().exists());
        assertTrue(outputPath.toFile().length() > 0);
    }
}
