// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import de.christianmahnke.iiif.fliiifenleger.source.GainMapData;
import de.christianmahnke.iiif.fliiifenleger.source.GainMapSource;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.*;

public class Tiler {

    public static final int DEFAULT_TILE_SIZE = 512;
    public static final ImageInfo.IIIFVersion DEFAULT_IIIF_VERSION = ImageInfo.IIIFVersion.V2;

    private static final Logger log = LoggerFactory.getLogger(Tiler.class);

    public static final Map<String, ImageSource> SOURCE_REGISTRY = loadSources();
    public static final Map<String, TileSink> SINK_REGISTRY = loadSinks();

    private final int defaultTileSize;
    private final ImageInfo.IIIFVersion defaultIiifVersion;

    protected static Map<String, ImageSource> loadSources() {
        Map<String, ImageSource> sources = new ConcurrentHashMap<>();
        ServiceLoader.load(ImageSource.class).forEach(source -> {
            sources.put(source.getName(), source);
        });
        return sources;
    }

    protected static Map<String, TileSink> loadSinks() {
        Map<String, TileSink> sinks = new ConcurrentHashMap<>();
        ServiceLoader.load(TileSink.class).forEach(sink -> {
            sinks.put(sink.getName(), sink);
        });
        return sinks;
    }

    /**
     * Default constructor.
     */
    public Tiler() {
        this(DEFAULT_TILE_SIZE, DEFAULT_IIIF_VERSION);
    }

    /**
     * Constructor to set default tiling parameters.
     * @param defaultTileSize The default tile size to use.
     * @param defaultIiifVersion The default IIIF version to use.
     */
    public Tiler(int defaultTileSize, ImageInfo.IIIFVersion defaultIiifVersion) {
        this.defaultTileSize = defaultTileSize;
        this.defaultIiifVersion = defaultIiifVersion;
    }

    public void createImages(
            ImageSource imageSource,
            List<Path> files,
            Path outputDir,
            String identifier,
            int zoomLevels,
            TileSink tileSink
    ) throws Exception {
        createImages(imageSource, files, outputDir, identifier, zoomLevels, this.defaultTileSize, this.defaultIiifVersion, tileSink);
    }

    protected void createImages(
            ImageSource imageSource,
            List<Path> files,
            Path outputDir,
            String identifier,
            int zoomLevels,
            int tileSize,
            ImageInfo.IIIFVersion version,
            TileSink sink
    ) throws Exception {
        System.out.printf("Processing %s...%n", files.get(0));

        int finalZoomLevels = zoomLevels;
        if (finalZoomLevels <= 0) {
            finalZoomLevels = ImageInfo.calculateZoomLevels(imageSource.getWidth(), imageSource.getHeight(), tileSize);
            log.info("Auto-calculated zoom levels to: {}", finalZoomLevels);
        }

        ImageInfo imageInfo = new ImageInfo(imageSource, tileSize, tileSize, finalZoomLevels, identifier, version);

        Path imageOutput = createImage(imageInfo, outputDir, version, sink);
        System.out.printf("Converted %s to %s%n", files.get(0), imageOutput);
    }

    public Path createImage(
            ImageInfo imageInfo,
            Path outputDir,
            ImageInfo.IIIFVersion version,
            TileSink sink
    ) throws Exception {
        log.info("Generating IIIF Image API {} metadata and tiles.", version.getExactVersion());
        generateTiles(imageInfo, outputDir, version, sink);

        Path outputImageDir = outputDir;

        Path infoJsonPath = outputImageDir.resolve("info.json");

        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        log.debug("Writing info.json to {}", infoJsonPath);
        mapper.writeValue(infoJsonPath.toFile(), imageInfo.toJson());
        return outputImageDir;
    }

    private void generateTiles(ImageInfo imageInfo, Path outputDir, ImageInfo.IIIFVersion version, TileSink sink) throws Exception {
        //Path imageBaseDir = sink.getBasePath(outputDir, imageInfo);
        Path imageBaseDir = outputDir;
        System.out.println("Generating tiles in: " + imageBaseDir);
        
        // Use a fixed thread pool to control parallelism
        int coreCount = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(coreCount);
        log.info("Using a thread pool with {} workers for tile generation.", coreCount);
        
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            generateSizes(imageInfo, imageBaseDir, version, sink, executor, futures);
            generateScaleTiles(imageInfo, imageBaseDir, version, sink, executor, futures);
            
            // Wait for all tasks to complete
            for (Future<?> future : futures) {
                future.get(); // This will rethrow exceptions from tasks
            }
        } finally {
            executor.shutdown();
        }
    }

    private void generateSizes(ImageInfo imageInfo, Path imageDir, ImageInfo.IIIFVersion version, TileSink sink, ExecutorService executor, List<Future<?>> futures) {
        for (ImageInfo.Size size : imageInfo.getSizes()) {
            futures.add(executor.submit(() -> {
                try {
                    BufferedImage scaledImage = imageInfo.getImage().crop(0, 0, imageInfo.getImage().getWidth(), imageInfo.getImage().getHeight(), (double) imageInfo.getImage().getWidth() / size.width());

                    String sizeStr = (version == ImageInfo.IIIFVersion.V3) ? String.format("%d,%d", size.width(), size.height()) : String.format("%d,", size.width());

                    Path outputPath = imageDir.resolve(String.format("full/%s/0/default.%s", sizeStr, sink.getFormatExtension()));
                    Files.createDirectories(outputPath.getParent());
                    log.debug("Writing tile to {}", outputPath);
                    try (OutputStream os = Files.newOutputStream(outputPath)) {
                        Map<String, Object> meta = withRegion(imageInfo.getImage().getMetadata(), 0, 0, size.width(), size.height(), 1);
                        withGainMap(imageInfo, meta, 0, 0, size.width(), size.height());
                        sink.saveTile(os, scaledImage, meta);
                    }

                    if (size.width() == imageInfo.getImage().getWidth() && size.height() == imageInfo.getImage().getHeight()) {
                        String fullSizeStr = (version == ImageInfo.IIIFVersion.V3) ? "max" : "full";
                        Path fullOutputPath = imageDir.resolve(String.format("full/%s/0/default.%s", fullSizeStr, sink.getFormatExtension()));
                        Files.createDirectories(fullOutputPath.getParent());
                        log.debug("Writing tile to {}", fullOutputPath);
                        try (OutputStream os = Files.newOutputStream(fullOutputPath)) {
                            Map<String, Object> fullMeta = withRegion(imageInfo.getImage().getMetadata(), 0, 0, size.width(), size.height(), 1);
                            withGainMap(imageInfo, fullMeta, 0, 0, size.width(), size.height());
                            sink.saveTile(os, scaledImage, fullMeta);
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to generate size " + size, e);
                }
            }));
        }
    }

    private void generateScaleTiles(ImageInfo imageInfo, Path imageDir, ImageInfo.IIIFVersion version, TileSink sink, ExecutorService executor, List<Future<?>> futures) {
        for (int scale : imageInfo.getScaleFactors()) {
            futures.add(executor.submit(() -> {
                try {
                    double scaleLevelWidth = (double) imageInfo.getImage().getWidth() / scale;
                    double scaleLevelHeight = (double) imageInfo.getImage().getHeight() / scale;

                    int tileNumWidth = (int) Math.ceil(scaleLevelWidth / imageInfo.getTileWidth());
                    int tileNumHeight = (int) Math.ceil(scaleLevelHeight / imageInfo.getTileHeight());

                    for (int x = 0; x < tileNumWidth; x++) {
                        for (int y = 0; y < tileNumHeight; y++) {
                            int tileX = x * imageInfo.getTileWidth() * scale;
                            int tileY = y * imageInfo.getTileHeight() * scale;

                            int scaledTileWidth = Math.min(imageInfo.getTileWidth() * scale, imageInfo.getImage().getWidth() - tileX);
                            int scaledTileHeight = Math.min(imageInfo.getTileHeight() * scale, imageInfo.getImage().getHeight() - tileY);

                            int tiledWidthCalc = (int) Math.ceil((double) scaledTileWidth / scale);
                            int tiledHeightCalc = (int) Math.ceil((double) scaledTileHeight / scale);

                            String url = (version == ImageInfo.IIIFVersion.V3) ? String.format("%d,%d,%d,%d/%d,%d/0/default.%s", tileX, tileY, scaledTileWidth, scaledTileHeight, tiledWidthCalc, tiledHeightCalc, sink.getFormatExtension()) : String.format("%d,%d,%d,%d/%d/0/default.%s", tileX, tileY, scaledTileWidth, scaledTileHeight, tiledWidthCalc, sink.getFormatExtension());

                            Path outputFile = imageDir.resolve(url);
                            Files.createDirectories(outputFile.getParent());
                            log.debug("Writing tile to {}", outputFile);

                            BufferedImage tileImg = imageInfo.getImage().crop(tileX, tileY, scaledTileWidth, scaledTileHeight, scale);
                            try (OutputStream os = Files.newOutputStream(outputFile)) {
                                Map<String, Object> meta = withRegion(imageInfo.getImage().getMetadata(), tileX, tileY, scaledTileWidth, scaledTileHeight, scale);
                                withGainMap(imageInfo, meta, tileX, tileY, scaledTileWidth, scaledTileHeight);
                                sink.saveTile(os, tileImg, meta);
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to generate tiles for scale " + scale, e);
                }
            }));
        }
    }

    /**
     * Enriches the given metadata map with the cropped gain map tile when the
     * image source carries an UltraHDR gain map (see {@link GainMapSource}).
     * <p>The gain map is cropped with the region mapped from primary-image
     * coordinates through the {@code primaryWidth / gainmapWidth} ratio,
     * rounding outwards (floor for the start, ceiling for the end) so the
     * crop always fully covers the tile region; ISO 21496-1 readers scale
     * the gain map back to the primary dimensions, so slight over-coverage
     * at tile edges is harmless.
     *
     * @param imageInfo The image info (primary dimensions).
     * @param metadata  The metadata map to enrich.
     * @param x         Tile region X in primary-image pixels.
     * @param y         Tile region Y in primary-image pixels.
     * @param w         Tile region width in primary-image pixels.
     * @param h         Tile region height in primary-image pixels.
     */
    private void withGainMap(ImageInfo imageInfo, Map<String, Object> metadata,
                             int x, int y, int w, int h) {
        if (!(imageInfo.getImage() instanceof GainMapSource gainMapSource)) {
            return;
        }
        try {
            GainMapData gainMap = gainMapSource.getGainMap();
            if (gainMap == null) {
                return;
            }

            double ratioX = (double) imageInfo.getImage().getWidth() / gainMap.width();
            double ratioY = (double) imageInfo.getImage().getHeight() / gainMap.height();

            int gx0 = (int) Math.floor(x / ratioX);
            int gy0 = (int) Math.floor(y / ratioY);
            int gx1 = (int) Math.ceil((x + w) / ratioX);
            int gy1 = (int) Math.ceil((y + h) / ratioY);
            gx0 = Math.max(0, Math.min(gx0, gainMap.width() - 1));
            gy0 = Math.max(0, Math.min(gy0, gainMap.height() - 1));
            gx1 = Math.max(gx0 + 1, Math.min(gx1, gainMap.width()));
            gy1 = Math.max(gy0 + 1, Math.min(gy1, gainMap.height()));

            BufferedImage gainmapImage = ImageIO.read(new ByteArrayInputStream(gainMap.gainmapJpeg()));
            if (gainmapImage == null) {
                log.warn("Gain map image could not be decoded; tile will not carry a gain map");
                return;
            }
            // A copy, not a subimage view: the source image may be reused for
            // the next tile while this one is encoded asynchronously.
            BufferedImage cropped = gainmapImage.getSubimage(gx0, gy0, gx1 - gx0, gy1 - gy0);
            BufferedImage copy = new BufferedImage(cropped.getWidth(), cropped.getHeight(), cropped.getType());
            Graphics2D g = copy.createGraphics();
            try {
                g.drawImage(cropped, 0, 0, null);
            } finally {
                g.dispose();
            }

            metadata.put(GainMapData.META_IMAGE, copy);
            metadata.put(GainMapData.META_METADATA, gainMap.metadataJson());
            metadata.put(GainMapData.META_X, gx0);
            metadata.put(GainMapData.META_Y, gy0);
            metadata.put(GainMapData.META_W, gx1 - gx0);
            metadata.put(GainMapData.META_H, gy1 - gy0);
        } catch (Exception e) {
            log.warn("Gain map crop failed; tile will not carry a gain map: {}", e.getMessage());
        }
    }

    /**
     * Returns a copy of the given metadata map enriched with the tile's
     * region in source-image coordinates.  Sinks that care about provenance
     * (e.g. the C2PA sink) can use this to describe which part of the
     * original image the tile depicts.
     *
     * @param metadata Original metadata (may be {@code null}); not modified.
     * @param x        Tile region X in source-image pixels.
     * @param y        Tile region Y in source-image pixels.
     * @param w        Tile region width in source-image pixels.
     * @param h        Tile region height in source-image pixels.
     * @param scale    Scale factor (1 = full resolution).
     * @return A new map with the {@code iiif.region.*} entries added.
     */
    private static Map<String, Object> withRegion(Map<String, Object> metadata, int x, int y, int w, int h, int scale) {
        Map<String, Object> result = (metadata == null) ? new java.util.HashMap<>() : new java.util.HashMap<>(metadata);
        result.put("iiif.region.x", x);
        result.put("iiif.region.y", y);
        result.put("iiif.region.w", w);
        result.put("iiif.region.h", h);
        result.put("iiif.region.scale", scale);
        return result;
    }
}
