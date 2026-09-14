// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import de.christianmahnke.iiif.fliiifenleger.sink.TileEnricher;
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

    /**
     * Per-tile metadata contributors, discovered via {@link ServiceLoader}.
     * Core ships {@link de.christianmahnke.iiif.fliiifenleger.sink.RegionTileEnricher};
     * the {@code ultrahdr} module adds the gain-map crop.  Enrichers must be
     * stateless: tiles are generated concurrently.
     */
    protected static final List<TileEnricher> TILE_ENRICHERS = loadEnrichers();

    protected final int defaultTileSize;
    protected final ImageInfo.IIIFVersion defaultIiifVersion;

    /**
     * System property for the tile-generation worker count.  Honoured when no
     * explicit count was set via {@link #setTileWorkers}.
     */
    public static final String WORKERS_PROPERTY = "tiler.workers";

    /**
     * Tile-generation worker count ({@code 0} = automatic: system property,
     * else available processors).
     */
    protected int tileWorkers = 0;

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

    protected static List<TileEnricher> loadEnrichers() {
        List<TileEnricher> enrichers = new java.util.ArrayList<>();
        ServiceLoader.load(TileEnricher.class).forEach(enrichers::add);
        if (enrichers.isEmpty()) {
            log.debug("No TileEnricher implementations found; tiles carry only source metadata.");
        } else {
            log.debug("Loaded TileEnricher implementations: {}",
                    enrichers.stream().map(enricher -> enricher.getClass().getName()).toList());
        }
        return java.util.Collections.unmodifiableList(enrichers);
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

    /**
     * Sets the tile-generation worker count for subsequent
     * {@code createImage(s)} calls on this instance.
     *
     * @param workers Worker threads; {@code 0} selects automatic sizing
     *                ({@code -Dtiler.workers}, else available processors).
     * @throws IllegalArgumentException if {@code workers} is negative.
     */
    public void setTileWorkers(int workers) {
        if (workers < 0) {
            throw new IllegalArgumentException(
                "Tile worker count must be >= 0 (0 = automatic), got " + workers);
        }
        this.tileWorkers = workers;
    }

    /**
     * @return The effective tile-generation worker count.
     */
    public int getTileWorkers() {
        if (tileWorkers > 0) {
            return tileWorkers;
        }
        String raw = System.getProperty(WORKERS_PROPERTY);
        if (raw != null && !raw.isBlank()) {
            try {
                int configured = Integer.parseInt(raw.trim());
                if (configured < 1) {
                    throw new IllegalArgumentException(
                        "Invalid -D" + WORKERS_PROPERTY + " value '" + raw
                        + "': expected a positive integer");
                }
                return configured;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Invalid -D" + WORKERS_PROPERTY + " value '" + raw
                    + "': expected a positive integer", e);
            }
        }
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * Creates a IIIF Presentation API manifest for a single image alongside tile generation.
     *
     * @param imageInfo The image info used to generate tiles.
     * @param outputDir The output directory where the manifest.json will be written.
     * @param baseUri   The base URI for the manifest.
     * @param version   The IIIF Presentation API version (V2 or V3).
     * @return The path to the generated manifest.json.
     * @throws Exception if the manifest cannot be generated or written.
     */
    public Path createManifest(
            ImageInfo imageInfo,
            Path outputDir,
            String baseUri,
            ImageInfo.IIIFVersion version
    ) throws Exception {
        IiifManifest manifest = new IiifManifest(version, baseUri, imageInfo.getIdentifier());
        String imagePath = imageInfo.getImage().getUrl().getPath();
        String imageId = imageInfo.getIdentifier() + imagePath.substring(imagePath.lastIndexOf('/') + 1)
                .replaceFirst("[.][^.]+$", "");
        manifest.addCanvas(imageId, imageInfo.getIdentifier(), imageId,
                imageInfo.getImage().getWidth(), imageInfo.getImage().getHeight());

        JsonNode manifestJson = manifest.toJson();
        Path manifestPath = outputDir.resolve("manifest.json");
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        log.info("Writing manifest.json to {}", manifestPath);
        mapper.writeValue(manifestPath.toFile(), manifestJson);
        return manifestPath;
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

    /**
     * Creates images with explicit tile size and IIIF version.
     *
     * @param imageSource Source image (already loaded).
     * @param files Source file paths (first entry used for logging).
     * @param outputDir Output directory.
     * @param identifier Identifier prefix for {@code info.json}.
     * @param zoomLevels Number of zoom levels (0 = auto-calculate).
     * @param tileSize Tile size in pixels.
     * @param version IIIF Image API version (V2 default, V3 opt-in).
     * @param sink Tile sink used for rendering.
     */
    public void createImages(
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
        ImageInfo.IIIFVersion effective = imageInfo.getVersion();
        if (version != effective) {
            log.warn("Version argument {} differs from ImageInfo version {}; using {}", version, effective, effective);
        }
        return createImage(imageInfo, outputDir, sink);
    }

    /**
     * Generates tiles and {@code info.json}, merging the sink's
     * {@link TileSink.InfoExtension} into the document.
     *
     * <p>The extension is resolved <em>before</em> tiling so incompatible
     * combinations (e.g. C2PA {@code trust-anchor} with Image API 2, which
     * has no place for namespaced properties) fail fast without writing tiles.
     */
    public Path createImage(
            ImageInfo imageInfo,
            Path outputDir,
            TileSink sink
    ) throws Exception {
        ImageInfo.IIIFVersion version = imageInfo.getVersion();
        TileSink.InfoExtension extension = sink == null
                ? TileSink.InfoExtension.empty()
                : sink.getInfoJsonExtension(version);
        Map<String, Object> infoJson = buildInfoJson(imageInfo, extension);
        log.info("Generating IIIF Image API {} metadata and tiles.", version.getExactVersion());
        generateTiles(imageInfo, outputDir, version, sink);

        Path outputImageDir = outputDir;

        Path infoJsonPath = outputImageDir.resolve("info.json");

        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        log.debug("Writing info.json to {}", infoJsonPath);
        mapper.writeValue(infoJsonPath.toFile(), infoJson);
        return outputImageDir;
    }

    /**
     * Merges a sink extension fragment into the base {@code info.json} map.
     *
     * <ul>
     *   <li>V3: extra contexts are prepended to {@code @context} (IIIF context
     *       stays last), services are appended to {@code service}, features to
     *       {@code extraFeatures}.</li>
     *   <li>V2: contexts must be empty (fixed {@code @context}); features are
     *       appended to the embedded profile {@code supports} list, services
     *       (if any, without namespaced properties) to {@code service}.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if extension contexts are requested for V2.
     */
    static Map<String, Object> buildInfoJson(ImageInfo imageInfo, TileSink.InfoExtension extension) {
        Map<String, Object> json = new java.util.LinkedHashMap<>(imageInfo.toJson());
        if (extension == null || extension.isEmpty()) {
            return json;
        }
        if (imageInfo.getVersion() == ImageInfo.IIIFVersion.V3) {
            if (!extension.contexts().isEmpty()) {
                java.util.LinkedHashSet<String> contexts = new java.util.LinkedHashSet<>(extension.contexts());
                Object existing = json.get("@context");
                if (existing instanceof String s) {
                    contexts.add(s);
                } else if (existing instanceof java.util.List<?> list) {
                    for (Object o : list) {
                        contexts.add(String.valueOf(o));
                    }
                }
                json.put("@context", new java.util.ArrayList<>(contexts));
            }
            if (!extension.services().isEmpty()) {
                java.util.ArrayList<Object> services = new java.util.ArrayList<>();
                Object existing = json.get("service");
                if (existing instanceof java.util.List<?> list) {
                    services.addAll(list);
                } else if (existing != null) {
                    services.add(existing);
                }
                services.addAll(extension.services());
                json.put("service", services);
            }
            if (!extension.features().isEmpty()) {
                java.util.LinkedHashSet<String> features = new java.util.LinkedHashSet<>();
                Object existing = json.get("extraFeatures");
                if (existing instanceof java.util.List<?> list) {
                    for (Object o : list) {
                        features.add(String.valueOf(o));
                    }
                }
                features.addAll(extension.features());
                json.put("extraFeatures", new java.util.ArrayList<>(features));
            }
        } else { // V2
            if (!extension.contexts().isEmpty()) {
                throw new IllegalArgumentException(
                        "Extension requires additional JSON-LD contexts, which IIIF Image API 2 does not support "
                        + "(fixed @context). Use IIIF Image API 3 for namespaced info.json options.");
            }
            if (!extension.features().isEmpty()) {
                java.util.ArrayList<Object> profile = new java.util.ArrayList<>();
                Object existing = json.get("profile");
                String compliance = "http://iiif.io/api/image/2/level2.json";
                Map<String, Object> embedded = null;
                if (existing instanceof java.util.List<?> list) {
                    for (Object o : list) {
                        if (o instanceof String s && s.contains("/api/image/2/level")) {
                            compliance = s;
                        } else if (o instanceof Map<?, ?> m) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> copy = new java.util.LinkedHashMap<>((Map<String, Object>) m);
                            embedded = copy;
                        } else {
                            profile.add(o);
                        }
                    }
                }
                profile.add(compliance);
                java.util.LinkedHashSet<String> supports = new java.util.LinkedHashSet<>();
                if (embedded != null && embedded.get("supports") instanceof java.util.List<?> sl) {
                    for (Object o : sl) {
                        supports.add(String.valueOf(o));
                    }
                }
                supports.addAll(extension.features());
                Map<String, Object> embeddedOut = embedded != null ? embedded : new java.util.LinkedHashMap<>();
                embeddedOut.put("supports", new java.util.ArrayList<>(supports));
                profile.add(embeddedOut);
                json.put("profile", profile);
            }
            if (!extension.services().isEmpty()) {
                java.util.ArrayList<Object> services = new java.util.ArrayList<>();
                Object existing = json.get("service");
                if (existing instanceof java.util.List<?> list) {
                    services.addAll(list);
                } else if (existing != null) {
                    services.add(existing);
                }
                services.addAll(extension.services());
                json.put("service", services);
            }
        }
        return json;
    }

    protected void generateTiles(ImageInfo imageInfo, Path outputDir, ImageInfo.IIIFVersion version, TileSink sink) throws Exception {
        //Path imageBaseDir = sink.getBasePath(outputDir, imageInfo);
        Path imageBaseDir = outputDir;
        System.out.println("Generating tiles in: " + imageBaseDir);
        
        // Use a fixed thread pool to control parallelism
        int workers = getTileWorkers();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        log.info("Using a thread pool with {} workers for tile generation.", workers);
        
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

    protected void generateSizes(ImageInfo imageInfo, Path imageDir, ImageInfo.IIIFVersion version, TileSink sink, ExecutorService executor, List<Future<?>> futures) {
        for (ImageInfo.Size size : imageInfo.getSizes()) {
            futures.add(executor.submit(() -> {
                try {
                    BufferedImage scaledImage = imageInfo.getImage().crop(0, 0, imageInfo.getImage().getWidth(), imageInfo.getImage().getHeight(), (double) imageInfo.getImage().getWidth() / size.width());

                    String sizeStr = (version == ImageInfo.IIIFVersion.V3) ? String.format("%d,%d", size.width(), size.height()) : String.format("%d,", size.width());

                    Path outputPath = imageDir.resolve(String.format("full/%s/0/default.%s", sizeStr, sink.getFormatExtension()));
                    Files.createDirectories(outputPath.getParent());
                    log.debug("Writing tile to {}", outputPath);
                    try (OutputStream os = Files.newOutputStream(outputPath)) {
                        Map<String, Object> meta = enrichMetadata(imageInfo, 0, 0, size.width(), size.height(), 1);
                        sink.saveTile(os, scaledImage, meta);
                    }

                    if (size.width() == imageInfo.getImage().getWidth() && size.height() == imageInfo.getImage().getHeight()) {
                        String fullSizeStr = (version == ImageInfo.IIIFVersion.V3) ? "max" : "full";
                        Path fullOutputPath = imageDir.resolve(String.format("full/%s/0/default.%s", fullSizeStr, sink.getFormatExtension()));
                        Files.createDirectories(fullOutputPath.getParent());
                        log.debug("Writing tile to {}", fullOutputPath);
                        try (OutputStream os = Files.newOutputStream(fullOutputPath)) {
                            Map<String, Object> fullMeta = enrichMetadata(imageInfo, 0, 0, size.width(), size.height(), 1);
                            sink.saveTile(os, scaledImage, fullMeta);
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to generate size " + size, e);
                }
            }));
        }
    }

    protected void generateScaleTiles(ImageInfo imageInfo, Path imageDir, ImageInfo.IIIFVersion version, TileSink sink, ExecutorService executor, List<Future<?>> futures) {
        for (int scale : imageInfo.getScaleFactors()) {
            double scaleLevelWidth = (double) imageInfo.getImage().getWidth() / scale;
            double scaleLevelHeight = (double) imageInfo.getImage().getHeight() / scale;

            int tileNumWidth = (int) Math.ceil(scaleLevelWidth / imageInfo.getTileWidth());
            int tileNumHeight = (int) Math.ceil(scaleLevelHeight / imageInfo.getTileHeight());

            // One task per tile (not per scale level): tiles vary wildly in
            // cost (kilobyte thumbnails vs. multi-megabyte full-size tiles),
            // and coarse tasks leave workers idle behind a straggler level.
            for (int x = 0; x < tileNumWidth; x++) {
                for (int y = 0; y < tileNumHeight; y++) {
                    final int tileX = x * imageInfo.getTileWidth() * scale;
                    final int tileY = y * imageInfo.getTileHeight() * scale;

                    final int scaledTileWidth = Math.min(imageInfo.getTileWidth() * scale, imageInfo.getImage().getWidth() - tileX);
                    final int scaledTileHeight = Math.min(imageInfo.getTileHeight() * scale, imageInfo.getImage().getHeight() - tileY);

                    final int tiledWidthCalc = (int) Math.ceil((double) scaledTileWidth / scale);
                    final int tiledHeightCalc = (int) Math.ceil((double) scaledTileHeight / scale);

                    futures.add(executor.submit(() -> {
                        try {
                            String url = (version == ImageInfo.IIIFVersion.V3) ? String.format("%d,%d,%d,%d/%d,%d/0/default.%s", tileX, tileY, scaledTileWidth, scaledTileHeight, tiledWidthCalc, tiledHeightCalc, sink.getFormatExtension()) : String.format("%d,%d,%d,%d/%d/0/default.%s", tileX, tileY, scaledTileWidth, scaledTileHeight, tiledWidthCalc, sink.getFormatExtension());

                            Path outputFile = imageDir.resolve(url);
                            Files.createDirectories(outputFile.getParent());
                            log.debug("Writing tile to {}", outputFile);

                            BufferedImage tileImg = imageInfo.getImage().crop(tileX, tileY, scaledTileWidth, scaledTileHeight, scale);
                            try (OutputStream os = Files.newOutputStream(outputFile)) {
                                Map<String, Object> meta = enrichMetadata(imageInfo, tileX, tileY, scaledTileWidth, scaledTileHeight, scale);
                                sink.saveTile(os, tileImg, meta);
                            }
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to generate tile "
                                + tileX + "," + tileY + "," + scaledTileWidth + "," + scaledTileHeight
                                + " at scale " + scale, e);
                        }
                    }));
                }
            }
        }
    }

    /**
     * Builds the per-tile metadata map: a copy of the source metadata enriched
     * by every {@link TileEnricher} (tile region, gain-map crops, …).
     *
     * @param imageInfo The image info (source and primary dimensions).
     * @param x         Tile region X in source-image pixels.
     * @param y         Tile region Y in source-image pixels.
     * @param w         Tile region width in source-image pixels.
     * @param h         Tile region height in source-image pixels.
     * @param scale    Scale factor (1 = full resolution).
     * @return A new map with all enricher contributions applied.
     */
    protected static Map<String, Object> enrichMetadata(ImageInfo imageInfo, int x, int y, int w, int h, int scale) {
        Map<String, Object> sourceMetadata = imageInfo.getImage().getMetadata();
        Map<String, Object> result = (sourceMetadata == null)
                ? new java.util.HashMap<>()
                : new java.util.HashMap<>(sourceMetadata);
        for (TileEnricher enricher : TILE_ENRICHERS) {
            enricher.enrich(imageInfo.getImage(), x, y, w, h, scale, result);
        }
        return result;
    }
}
