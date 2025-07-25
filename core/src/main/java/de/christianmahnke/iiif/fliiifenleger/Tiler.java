package de.christianmahnke.iiif.fliiifenleger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
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
            int zoomLevels
    ) throws Exception {
        createImages(imageSource, files, outputDir, identifier, zoomLevels, this.defaultTileSize, this.defaultIiifVersion, SINK_REGISTRY.get("default"));
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

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
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
                        sink.saveTile(os, scaledImage, imageInfo.getImage().getMetadata());
                    }

                    if (size.width() == imageInfo.getImage().getWidth() && size.height() == imageInfo.getImage().getHeight()) {
                        String fullSizeStr = (version == ImageInfo.IIIFVersion.V3) ? "max" : "full";
                        Path fullOutputPath = imageDir.resolve(String.format("full/%s/0/default.%s", fullSizeStr, sink.getFormatExtension()));
                        Files.createDirectories(fullOutputPath.getParent());
                        log.debug("Writing tile to {}", fullOutputPath);
                        try (OutputStream os = Files.newOutputStream(fullOutputPath)) {
                            sink.saveTile(os, scaledImage, imageInfo.getImage().getMetadata());
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
                                sink.saveTile(os, tileImg, imageInfo.getImage().getMetadata());
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to generate tiles for scale " + scale, e);
                }
            }));
        }
    }
}
