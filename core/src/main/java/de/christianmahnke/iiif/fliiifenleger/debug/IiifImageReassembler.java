// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

package de.christianmahnke.iiif.fliiifenleger.debug;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches IIIF tiles from an Image API endpoint and reassembles them into a single image.
 */
public class IiifImageReassembler {

    private static final Logger log = LoggerFactory.getLogger(IiifImageReassembler.class);

    private final URL url;
    private JsonNode infoJson;
    private URI imageBaseUri;

    /**
     * Raw tile bytes collected during the last reassembly when C2PA
     * collection was requested (tile URL → asset bytes).
     */
    private final Map<String, byte[]> fetchedTileBytes = new ConcurrentHashMap<>();

    public IiifImageReassembler(URL url) {
        this.url = url;
    }

    /**
     * Fetches and parses the info.json file.
     *
     * @throws IOException if the info.json cannot be fetched or parsed.
     */
    public void load() throws IOException {
        log.info("Fetching info.json from: {}", url);
        log.debug("Loading data from URL: {}", url);
        try (InputStream is = url.openStream();
             Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            ObjectMapper mapper = new ObjectMapper();
            this.infoJson = mapper.readTree(reader);

            String imageIdStr = this.infoJson.has("@id")
                    ? this.infoJson.get("@id").asString()
                    : this.infoJson.get("id").asString();
            this.imageBaseUri = new URI(imageIdStr);

        } catch (URISyntaxException e) {
            throw new IOException("Could not determine image base URI from info.json", e);
        }
    }

    /**
     * Reassembles the full image from its tiles at the highest resolution.
     *
     * @return A BufferedImage containing the reassembled image.
     * @throws IllegalStateException if load() has not been called first.
     */
    public BufferedImage reassemble() {
        return reassemble(false);
    }

    /**
     * Reassembles the full image from its tiles at the highest resolution.
     *
     * @param collectC2paBytes When {@code true}, the raw bytes of every
     *                         fetched tile are collected (for C2PA checks)
     *                         and can be retrieved with
     *                         {@link #getFetchedTileBytes()}.
     * @return A BufferedImage containing the reassembled image.
     * @throws IllegalStateException if load() has not been called first.
     */
    public BufferedImage reassemble(boolean collectC2paBytes) {
        if (infoJson == null || imageBaseUri == null) {
            throw new IllegalStateException("info.json has not been loaded. Call load() first.");
        }
        fetchedTileBytes.clear();

        int fullWidth = infoJson.get("width").asInt(0);
        int fullHeight = infoJson.get("height").asInt(0);

        // Find the first tile definition (usually there's only one for static images)
        JsonNode tilesInfo = infoJson.get("tiles").get(0);
        int tileWidth = tilesInfo.get("width").asInt(0);
        // V2 info.json might not have height, so we default to tileWidth
        int tileHeight = tilesInfo.has("height")
                ? tilesInfo.get("height").asInt(tileWidth)
                : tileWidth;

        log.info("Image dimensions: {}x{}", fullWidth, fullHeight);
        log.info("Tile dimensions: {}x{}", tileWidth, tileHeight);

        int cols = (int) Math.ceil((double) fullWidth / tileWidth);
        int rows = (int) Math.ceil((double) fullHeight / tileHeight);
        log.info("Tile grid: {}x{} ({} tiles total)", cols, rows, cols * rows);

        BufferedImage finalImage = new BufferedImage(fullWidth, fullHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = finalImage.createGraphics();

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        String imageBase = imageBaseUri.toString();
        if (imageBase.endsWith("/")) {
            imageBase = imageBase.substring(0, imageBase.length() - 1);
        }

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int tileX = x * tileWidth;
                int tileY = y * tileHeight;
                int w = Math.min(tileWidth, fullWidth - tileX);
                int h = Math.min(tileHeight, fullHeight - tileY);

                // Construct the IIIF tile URL: {id}/{region}/{size}/{rotation}/{quality}.{format}
                String tileUrl = String.format("%s/%d,%d,%d,%d/full/0/default.jpg", imageBase, tileX, tileY, w, h);

                final int destX = tileX;
                final int destY = tileY;

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        log.debug("Fetching tile: {}", tileUrl);
                        byte[] tileBytes = new URI(tileUrl).toURL()
                            .openStream().readAllBytes();
                        if (collectC2paBytes) {
                            fetchedTileBytes.put(tileUrl, tileBytes);
                        }
                        BufferedImage tileImage = ImageIO.read(new ByteArrayInputStream(tileBytes));
                        if (tileImage != null) {
                            // Drawing must be synchronized
                            synchronized (g2d) {
                                g2d.drawImage(tileImage, destX, destY, null);
                            }
                        } else {
                            log.warn("Failed to load tile: {}", tileUrl);
                        }
                    } catch (IOException | URISyntaxException e) {
                        log.error("Error fetching tile {}: {}", tileUrl, e.getMessage());
                    }
                });
                futures.add(future);
            }
        }

        // Wait for all tiles to be downloaded and drawn
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        g2d.dispose();
        log.info("Image reassembly complete.");
        return finalImage;
    }

    /**
     * Saves the reassembled image to a file.
     *
     * @param image      The image to save.
     * @param outputPath The path where the image will be saved.
     * @param format     The image format (e.g., "jpg", "png").
     * @throws IOException if the image cannot be saved.
     */
    /**
     * Returns the raw bytes of the tiles fetched by the last
     * {@link #reassemble(boolean)} call with C2PA collection enabled.
     *
     * @return Tile URL → asset bytes.
     */
    public Map<String, byte[]> getFetchedTileBytes() {
        return Map.copyOf(fetchedTileBytes);
    }

    public void saveImage(BufferedImage image, Path outputPath, String format) throws IOException {
        log.debug("Writing reassembled image to: {}", outputPath);
        ImageIO.write(image, format, outputPath.toFile());
        log.info("Successfully saved image.");
    }
}
