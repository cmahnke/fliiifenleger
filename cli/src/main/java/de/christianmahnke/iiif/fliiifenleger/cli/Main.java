// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.cli;

import ch.qos.logback.classic.Level;
import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.IiifManifest;
import de.christianmahnke.iiif.fliiifenleger.ManifestMerger;
import de.christianmahnke.iiif.fliiifenleger.ManifestUpdater;
import de.christianmahnke.iiif.fliiifenleger.Tiler;
import de.christianmahnke.iiif.fliiifenleger.TilerException;
import de.christianmahnke.iiif.fliiifenleger.debug.IiifImageReassembler;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
import de.christianmahnke.jc2pa.TileSigner;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;


@Command(name = "fliiifenleger",
        mixinStandardHelpOptions = true,
        versionProvider = Main.VersionProvider.class,
        description = "A tool for generating and validating static IIIF images.",
        subcommands = {
                Main.GenerateCommand.class,
                Main.ValidateCommand.class,
                Main.ManifestCommand.class,
                CommandLine.HelpCommand.class,
                Main.InfoCommand.class
        })
public class Main implements Runnable {
    @Override
    public void run() {
        // This is executed if no subcommand is specified.
        new CommandLine(this).usage(System.out);
    }

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Option(names = {"-L", "--log-level"}, description = "Set the log level. Valid values: ${COMPLETION-CANDIDATES}",
            paramLabel = "<level>", scope = CommandLine.ScopeType.INHERIT)
    void setLogLevel(String levelStr) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.toLevel(levelStr, Level.INFO));
    }

    @Command(name = "generate",
            description = "Generates IIIF tiles from local image files.",
            mixinStandardHelpOptions = true)
    static class GenerateCommand implements Callable<Integer> {

        @Option(names = {"-i", "--identifier"}, description = "Set the identifier in the info.json.", defaultValue = "http://localhost:8887/iiif/")
        private String identifier;

        @Option(names = {"-z", "--zoom-levels"}, description = "Set the number of zoom levels. Set to 0 to auto-calculate.", defaultValue = "0")
        private int zoomLevels;

        @Option(names = {"-t", "--tile-size"}, description = "Set the tile size.", defaultValue = Tiler.DEFAULT_TILE_SIZE + "")
        private int tileSize;

        @Option(names = {"-o", "--output"}, description = "Directory where the IIIF images are generated.", defaultValue = "iiif")
        private Path output;

        @Option(names = {"-s", "--source"}, description = "The image source implementation to use.", defaultValue = "default")
        private String source;

        @Option(names = "--sink", description = "The image sink implementation to use for tiles.", defaultValue = "default")
        private String sink;

        @Option(names = "--source-opt", description = "Set an option for the image source (e.g., -Dkey=value).",
                mapFallbackValue = "")
        private Map<String, String> sourceOptions;

        @Option(names = "--sink-opt", description = "Set an option for the image sink (e.g., -Dkey=value).")
        private Map<String, String> sinkOptions;

        @Option(names = "--iiif-version", description = "Set the IIIF version. Options are V2, V3.", defaultValue = "V2")
        private ImageInfo.IIIFVersion version;

        @Option(names = "--validate-info", description = "Validate the generated info.json against the JSON Schema for the requested IIIF version.")
        private boolean validateInfo;

        @Option(names = {"-j", "--jobs"}, description = "Tile-generation worker threads per image. 0 selects automatic sizing (-Dtiler.workers, else available processors).", defaultValue = "0")
        private int jobs;

        @Option(names = {"-b", "--base-uri"}, description = "Base URI for the manifest when --manifest is enabled.")
        private String baseUri;

        @Option(names = {"-m", "--manifest"}, description = "Generate a IIIF Presentation API manifest alongside tiles.")
        private boolean manifest;

        @Parameters(index = "0..*", description = "Input image files to process.")
        private List<File> files;


        @Override
        public Integer call() throws Exception {
            if (files == null || files.isEmpty()) {
                log.error("Error: No input files specified for 'generate' command.");
                new CommandLine(this).usage(System.out);
                return 1;
            }
            if (tileSize <= 0) {
                log.error("Error: --tile-size must be positive, got {}", tileSize);
                return 1;
            }
            if (jobs < 0) {
                log.error("Error: --jobs must be >= 0 (0 = automatic), got {}", jobs);
                return 1;
            }
            if (version == null) {
                version = ImageInfo.IIIFVersion.V2;
            }

            java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(0);
            // Process files in parallel
            files.parallelStream().forEach(file -> {
                try {
                    String sourceName;
                    if (source != null && !source.isEmpty()) {
                        sourceName = source;
                    } else {
                        String fileName = file.getName();
                        int dotIndex = fileName.lastIndexOf('.');
                        String extension = (dotIndex == -1) ? "" : fileName.substring(dotIndex + 1);
                        switch (extension.toLowerCase()) {
                            case "jxl":
                                sourceName = "jxl";
                                break;
                            default:
                                sourceName = "default";
                                break;
                        }
                    }

                    ImageSource sourceTemplate = Tiler.SOURCE_REGISTRY.get(sourceName);
                    if (sourceTemplate == null) {
                        throw new TilerException("Unknown image source: '" + sourceName + "'");
                    }

                    // Create a new instance for each file
                    ImageSource imageSource = sourceTemplate.getClass().getConstructor().newInstance();

                    // Set the URL to trigger image loading
                    imageSource.load(file.toURI().toURL());

                    if (sourceOptions != null) {
                        imageSource.setOptions(sourceOptions);
                    }

                    TileSink sinkTemplate = Tiler.SINK_REGISTRY.get(sink);
                    if (sinkTemplate == null) {
                        throw new TilerException("Unknown image sink: '" + sink + "'");
                    }

                    // Create a new instance for each sink operation
                    TileSink tileSink = sinkTemplate.getClass().getConstructor().newInstance();

                    if (sinkOptions != null) {
                        tileSink.setOptions(sinkOptions);
                    }

                    try {
                    Tiler tiler = new Tiler();
                    tiler.setTileWorkers(jobs);
                    tiler.createImages(
                                imageSource,
                                List.of(file.toPath()),
                                output,
                                identifier,
                                zoomLevels,
                                tileSize,
                                version,
                                tileSink
                        );
                        if (manifest && baseUri != null && !baseUri.isEmpty()) {
                            int finalZoomLevels = zoomLevels <= 0
                                    ? ImageInfo.calculateZoomLevels(imageSource.getWidth(), imageSource.getHeight(), tileSize)
                                    : zoomLevels;
                            ImageInfo imageInfo = new ImageInfo(imageSource, tileSize, tileSize,
                                    finalZoomLevels, identifier, version);
                            tiler.createManifest(imageInfo, output, baseUri, version);
                        }
                        if (validateInfo) {
                            Path infoJson = output.resolve("info.json");
                            var result = de.christianmahnke.iiif.fliiifenleger.InfoJsonValidator.validate(infoJson, version);
                            if (!result.valid()) {
                                for (String error : result.errors()) {
                                    log.error("info.json schema error for {}: {}", file.getPath(), error);
                                }
                                throw new TilerException("Generated info.json failed schema validation for " + file.getPath());
                            }
                            log.info("info.json schema validation passed for {}", file.getPath());
                        }
                    } finally {
                        // Shut down lane pools promptly (c2pa/ultrahdr sinks);
                        // plain sinks are unaffected.
                        if (tileSink instanceof AutoCloseable closeable) {
                            try {
                                closeable.close();
                            } catch (Exception e) {
                                log.warn("Failed to close tile sink for {}: {}", file.getPath(), e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    // In a real parallel stream, you'd want a better way to collect errors.
                    // For this example, we just print it.
                    log.error("Failed to process file {}: {}", file.getPath(), e.getMessage(), e);
                    failures.incrementAndGet();
                    // To make the process fail, you could use a shared error collection or rethrow a runtime exception.
                }
            });

            return failures.get() == 0 ? 0 : 1; // Success
        }
    }

    @Command(name = "validate",
            description = "Validates a IIIF endpoint by reassembling the image from its tiles.",
            mixinStandardHelpOptions = true)
    static class ValidateCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "The URL of the info.json for the IIIF image to validate.")
        private String infoJsonUrl;

        @Option(names = {"-o", "--output"}, description = "Path to save the reassembled image.", required = true)
        private Path outputPath;

        @Option(names = {"-f", "--format"}, description = "Output image format (e.g., jpg, png).", defaultValue = "jpg")
        private String format;

        @Option(names = {"--check-c2pa"},
                description = "Check every fetched tile for a C2PA manifest. Exit code 2 if any tile has no (valid) manifest.")
        private boolean checkC2pa;

        @Option(names = {"--trust-anchor"},
                description = "PEM trust anchor bundle for --check-c2pa: tiles must validate as Trusted against it (exit code 2 otherwise). Without it, presence of a (valid) manifest suffices.")
        private Path trustAnchor;

        @Option(names = {"--schema"}, description = "Validate info.json against its JSON Schema before reassembly. Values: auto, 2, 3, off.", defaultValue = "auto")
        private String schemaMode;

        @Override
        public Integer call() {
            log.info("Starting validation for: {}", infoJsonUrl);
            try {
                if (!"off".equalsIgnoreCase(schemaMode)) {
                    ImageInfo.IIIFVersion expected = null;
                    if ("2".equals(schemaMode) || "v2".equalsIgnoreCase(schemaMode)) {
                        expected = ImageInfo.IIIFVersion.V2;
                    } else if ("3".equals(schemaMode) || "v3".equalsIgnoreCase(schemaMode)) {
                        expected = ImageInfo.IIIFVersion.V3;
                    } else if (!"auto".equalsIgnoreCase(schemaMode)) {
                        log.error("Invalid --schema value '{}': expected auto, 2, 3, or off.", schemaMode);
                        return 1;
                    }
                    var schemaResult = de.christianmahnke.iiif.fliiifenleger.InfoJsonValidator.validate(
                            new URI(infoJsonUrl).toURL(), expected);
                    if (!schemaResult.valid()) {
                        for (String error : schemaResult.errors()) {
                            log.error("info.json schema error: {}", error);
                        }
                        log.error("info.json schema validation failed for {}", infoJsonUrl);
                        return 1;
                    }
                    log.info("info.json schema validation passed (detected Image API {}).",
                            schemaResult.detectedVersion() != null ? schemaResult.detectedVersion().getShortName() : "?");
                }
                IiifImageReassembler reassembler = new IiifImageReassembler(new URI(infoJsonUrl).toURL());
                reassembler.load();
                BufferedImage fullImage = reassembler.reassemble(checkC2pa);
                reassembler.saveImage(fullImage, outputPath, format);
                log.info("Validation successful. Reassembled image saved to {}", outputPath);

                if (checkC2pa) {
                    return checkC2paManifests(reassembler.getFetchedTileBytes());
                }
            } catch (Exception e) {
                log.error("Validation failed: {}", e.getMessage(), e);
                return 1;
            }
            return 0;
        }

        /**
         * Reports the C2PA manifest status of every fetched tile.
         *
         * <p>Without {@code --trust-anchor}, presence of a (valid) manifest
         * suffices.  With it, every tile must validate as {@code Trusted}
         * against the anchor bundle.
         *
         * @return 0 when every tile passes, 2 otherwise.
         */
        private int checkC2paManifests(Map<String, byte[]> tileBytes) {
            int signed = 0;
            int unsigned = 0;
            try (TileSigner signer = new TileSigner((String) null)) {
                if (trustAnchor != null) {
                    String pem;
                    try {
                        pem = Files.readString(trustAnchor);
                    } catch (Exception e) {
                        log.error("Cannot read trust anchor bundle {}: {}", trustAnchor, e.getMessage());
                        return 1;
                    }
                    try {
                        signer.setTrustAnchors(pem);
                    } catch (Exception e) {
                        log.error("Invalid trust anchor bundle {}: {}", trustAnchor, e.getMessage());
                        return 1;
                    }
                    log.info("Validating C2PA manifests against trust anchor {}", trustAnchor);
                }
                for (Map.Entry<String, byte[]> tile : tileBytes.entrySet()) {
                    if (trustAnchor != null) {
                        String state;
                        try {
                            state = unquote(signer.validationState(tile.getValue(), "image/jpeg"));
                        } catch (Exception e) {
                            state = null;
                            log.warn("C2PA check failed for {}: {}", tile.getKey(), e.getMessage());
                        }
                        if ("Trusted".equalsIgnoreCase(state)) {
                            log.info("C2PA {} -> trusted manifest", tile.getKey());
                            signed++;
                        } else {
                            log.warn("C2PA {} -> not trusted (state: {})", tile.getKey(), state);
                            unsigned++;
                        }
                    } else {
                        String label;
                        try {
                            label = signer.activeLabel(tile.getValue(), "image/jpeg");
                        } catch (Exception e) {
                            label = null;
                            log.warn("C2PA check failed for {}: {}", tile.getKey(), e.getMessage());
                        }
                        if (label != null) {
                            log.info("C2PA {} -> manifest {}", tile.getKey(), label);
                            signed++;
                        } else {
                            log.warn("C2PA {} -> no valid manifest", tile.getKey());
                            unsigned++;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("C2PA checker failed: {}", e.getMessage(), e);
                return 1;
            } finally {
                // Trust anchors are process-global: never leak them into
                // later validations in the same JVM (e.g. test suites).
                if (trustAnchor != null) {
                    try (TileSigner cleaner = new TileSigner((String) null)) {
                        cleaner.clearTrustAnchors();
                    } catch (Exception e) {
                        log.warn("Could not clear trust anchors: {}", e.getMessage());
                    }
                }
            }
            log.info("C2PA summary: {} signed, {} unsigned of {} tiles", signed, unsigned,
                     signed + unsigned);
            return unsigned == 0 ? 0 : 2;
        }

        /**
         * Strip one pair of surrounding double quotes (the reader returns
         * JSON-encoded strings like {@code "Trusted"}).
         */
        private static String unquote(String value) {
            if (value != null && value.length() >= 2
                    && value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }
    }

    @Command(name = "manifest",
            description = "Merges IIIF Presentation API manifests, changes base URIs, and adds seeAlso entries (e.g. TEI, MusicXML).",
            mixinStandardHelpOptions = true)
    static class ManifestCommand implements Callable<Integer> {

        @Option(names = {"-b", "--base-uri"}, description = "The new base URI for the merged manifest.", required = true)
        private String baseUri;

        @Option(names = {"-v", "--version"}, description = "IIIF Presentation API version. Options are V2, V3.", defaultValue = "V2")
        private ImageInfo.IIIFVersion version;

        @Option(names = {"-o", "--output"}, description = "Path to save the merged manifest.json.")
        private Path output;

        @Option(names = {"-t", "--tei-folder"}, description = "Path to folder containing TEI XML files to add as seeAlso (deprecated, use --seealso-folder).")
        private Path teiFolder;

        @Option(names = {"--tei-base-url"}, description = "Base URL for TEI files (default: manifest base URI, deprecated, use --seealso-base-url).")
        private String teiBaseUrl;

        @Option(names = {"--seealso-folder"}, description = "Path to folder containing files to add as canvas seeAlso entries.")
        private Path seeAlsoFolder;

        @Option(names = {"--seealso-base-url"}, description = "Base URL for seeAlso files (default: manifest base URI).")
        private String seeAlsoBaseUrl;

        @Option(names = {"--seealso-pattern"}, description = "Glob pattern for seeAlso file names, e.g. '*.tei.xml' or '*.musicxml' (default: legacy *.xml and *.tei, case-insensitive).")
        private String seeAlsoPattern;

        @Option(names = {"--seealso-format", "--seealso-media-type", "--media-type"}, description = "Media type for seeAlso entries (default: auto-detected per file, e.g. application/tei+xml, application/vnd.recordare.musicxml+xml).")
        private String seeAlsoFormat;

        @Option(names = {"--seealso-type"}, description = "Type for seeAlso entries (default: Dataset).", defaultValue = "Dataset")
        private String seeAlsoType;

        @Option(names = {"--seealso-profile"}, description = "Profile for seeAlso entries (default: per-format default, empty string omits it).")
        private String seeAlsoProfile;

        @Parameters(index = "0..*", description = "Input manifest.json files or URLs.")
        private List<String> inputs;

        @Override
        public Integer call() throws Exception {
            if (inputs == null || inputs.isEmpty()) {
                log.error("Error: No manifest inputs specified for 'manifest' command.");
                new CommandLine(this).usage(System.out);
                return 1;
            }
            if (baseUri == null || baseUri.isEmpty()) {
                log.error("Error: --base-uri is required.");
                return 1;
            }
            if (version == null) {
                version = ImageInfo.IIIFVersion.V2;
            }

            Path outputPath = output;
            if (output == null || output.toString().isEmpty()) {
                outputPath = Path.of("manifest.json");
            }

            if (seeAlsoFolder != null || teiFolder != null) {
                if (inputs.size() != 1) {
                    log.error("Error: When using --seealso-folder/--tei-folder, exactly one input manifest is required.");
                    return 1;
                }
                Path folder = seeAlsoFolder != null ? seeAlsoFolder : teiFolder;
                String fileBaseUrl = seeAlsoBaseUrl != null ? seeAlsoBaseUrl : teiBaseUrl;
                String manifestJson = readInput(inputs.get(0));
                String result = ManifestUpdater.updateWithSeeAlsoFiles(manifestJson, folder, seeAlsoPattern,
                        fileBaseUrl, seeAlsoFormat, seeAlsoType, seeAlsoProfile);
                java.nio.file.Files.writeString(outputPath, result);
                log.info("Updated manifest with seeAlso written to {}", outputPath);
            } else {
                ManifestMerger.mergeAndSave(inputs, version, baseUri, outputPath);
                ManifestMerger merger = new ManifestMerger(version, baseUri);
                log.info("Merged manifest written to {}", outputPath);
            }
            return 0;
        }

        private String readInput(String input) throws Exception {
            try {
                return new String(java.nio.file.Files.readAllBytes(Path.of(input)));
            } catch (Exception e) {
                try (java.io.InputStream is = new java.net.URI(input).toURL().openStream()) {
                    return new String(is.readAllBytes());
                }
            }
        }
    }

    @Command(name = "info",
            description = "Display information about available components.",
            mixinStandardHelpOptions = true,
            subcommands = {
                    InfoCommand.ListSourcesCommand.class,
                    InfoCommand.ListSinksCommand.class
            })
    static class InfoCommand implements Runnable {
        @Override
        public void run() {
            new CommandLine(this).usage(System.out);
        }

        @Command(name = "list-sources", description = "List all available image sources.")
        static class ListSourcesCommand implements Callable<Integer> {
            @Override
            public Integer call() {
                if (Tiler.SOURCE_REGISTRY.isEmpty()) {
                    System.out.println("No image sources found. Make sure they are on the classpath and registered via @AutoService.");
                } else {
                    System.out.println("Available image sources:");
                    Tiler.SOURCE_REGISTRY.keySet().forEach(key -> System.out.println(" - " + key));
                }
                return 0;
            }
        }

        @Command(name = "list-sinks", description = "List all available image sinks.")
        static class ListSinksCommand implements Callable<Integer> {
            @Override
            public Integer call() {
                System.out.println("Available image sinks:");
                Tiler.SINK_REGISTRY.keySet().forEach(key -> System.out.println(" - " + key));
                return 0;
            }
        }
    }

    static class VersionProvider implements CommandLine.IVersionProvider {
        public String[] getVersion() {
            try (InputStream input = Main.class.getClassLoader().getResourceAsStream("version.properties")) {
                Properties prop = new Properties();
                if (input == null) {
                    log.error("Sorry, unable to find version.properties");
                    return new String[]{"fliiifenleger: unknown version"};
                }
                prop.load(input);
                return new String[]{"fliiifenleger " + prop.getProperty("version")};
            } catch (Exception ex) {
                return new String[]{"fliiifenleger: error reading version"};
            }
        }
    }

    public static void main(String[] args) {
        // This is a headless CLI: pin headless mode before any AWT class
        // loads.  Without it, macOS initializes AppKit on first Toolkit
        // touch — from a worker thread that blocks forever (native images
        // do not inherit a usable display session either).  A
        // -Djava.awt.headless build flag alone does NOT propagate to
        // runtime defaults, so this must be set programmatically.
        System.setProperty("java.awt.headless", "true");
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}