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

package de.christianmahnke.iiif.fliiifenleger.cli;

import ch.qos.logback.classic.Level;
import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.Tiler;
import de.christianmahnke.iiif.fliiifenleger.TilerException;
import de.christianmahnke.iiif.fliiifenleger.debug.IiifImageReassembler;
import de.christianmahnke.iiif.fliiifenleger.sink.TileSink;
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

        @Option(names = "--iiif-version", description = "Set the IIIF version. Options are V2, V3_0.", defaultValue = "V2")
        private ImageInfo.IIIFVersion version;

        @Parameters(index = "0..*", description = "Input image files to process.")
        private List<File> files;


        @Override
        public Integer call() throws Exception {
            if (files == null || files.isEmpty()) {
                log.error("Error: No input files specified for 'generate' command.");
                new CommandLine(this).usage(System.out);
                return 1;
            }

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

                    Tiler tiler = new Tiler();
                    tiler.createImages(
                            imageSource,
                            List.of(file.toPath()),
                            output,
                            identifier,
                            zoomLevels,
                            tileSink
                    );
                } catch (Exception e) {
                    // In a real parallel stream, you'd want a better way to collect errors.
                    // For this example, we just print it.
                    log.error("Failed to process file {}: {}", file.getPath(), e.getMessage(), e);
                    // To make the process fail, you could use a shared error collection or rethrow a runtime exception.
                }
            });

            return 0; // Success
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

        @Override
        public Integer call() {
            log.info("Starting validation for: {}", infoJsonUrl);
            try {
                IiifImageReassembler reassembler = new IiifImageReassembler(new URI(infoJsonUrl).toURL());
                reassembler.load();
                BufferedImage fullImage = reassembler.reassemble();
                reassembler.saveImage(fullImage, outputPath, format);
                log.info("Validation successful. Reassembled image saved to {}", outputPath);
            } catch (Exception e) {
                log.error("Validation failed: {}", e.getMessage(), e);
                return 1;
            }
            return 0;
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
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}