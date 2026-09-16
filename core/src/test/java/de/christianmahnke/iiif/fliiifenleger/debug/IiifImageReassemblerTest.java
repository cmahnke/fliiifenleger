// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.debug;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
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

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
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

    private static final String HDR_PROFILE = "https://christianmahnke.de/iiif/hdr/";

    /** Stubs an info.json with the given body and loads a fresh reassembler. */
    private IiifImageReassembler loadInfo(String infoJsonBody) throws Exception {
        String path = "/hdr-info/info.json";
        server.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(infoJsonBody)));
        IiifImageReassembler reassembler =
                new IiifImageReassembler(new URI(server.baseUrl() + path).toURL());
        reassembler.load();
        return reassembler;
    }

    @Test
    void isHdrEndpoint_detectsV2EmbeddedSupports() throws Exception {
        String json = "{\"@id\":\"" + server.baseUrl() + "/iiif/2/test-image\","
                + "\"profile\":[\"http://iiif.io/api/image/2/level2.json\","
                + "{\"supports\":[\"" + HDR_PROFILE + "\"]}]}";
        assertTrue(loadInfo(json).isHdrEndpoint(HDR_PROFILE));
    }

    @Test
    void isHdrEndpoint_detectsV3ExtraFeatures() throws Exception {
        String json = "{\"id\":\"" + server.baseUrl() + "/iiif/3/test-image\","
                + "\"extraFeatures\":[\"" + HDR_PROFILE + "\"]}";
        assertTrue(loadInfo(json).isHdrEndpoint(HDR_PROFILE));
    }

    @Test
    void isHdrEndpoint_detectsV3ServiceProfile() throws Exception {
        String json = "{\"id\":\"" + server.baseUrl() + "/iiif/3/test-image\","
                + "\"service\":[{\"id\":\"" + HDR_PROFILE + "\",\"type\":\"Service\","
                + "\"profile\":\"" + HDR_PROFILE + "\"}]}";
        assertTrue(loadInfo(json).isHdrEndpoint(HDR_PROFILE));
    }

    @Test
    void isHdrEndpoint_returnsFalseWithoutMarker() throws Exception {
        String json = "{\"id\":\"" + server.baseUrl() + "/iiif/3/test-image\","
                + "\"profile\":\"level2\"}";
        assertFalse(loadInfo(json).isHdrEndpoint(HDR_PROFILE));
    }

    @Test
    void reassemble_countsFailedTiles() throws Exception {
        // Baseline: all four tiles decode cleanly -> no failures.
        IiifImageReassembler reassembler = new IiifImageReassembler(infoJsonUrl);
        reassembler.load();
        reassembler.reassemble();
        assertEquals(0, reassembler.getFailedTileCount());

        // Break one tile: ImageIO.decode returns null on an empty body,
        // which the reassembler counts as a failure.
        server.stubFor(get(urlEqualTo("/iiif/2/test-image/1,1,1,1/full/0/default.jpg"))
                .willReturn(aResponse().withHeader("Content-Type", "image/jpeg")
                        .withBody(new byte[0])));
        reassembler.reassemble();
        assertEquals(1, reassembler.getFailedTileCount());
    }
}
