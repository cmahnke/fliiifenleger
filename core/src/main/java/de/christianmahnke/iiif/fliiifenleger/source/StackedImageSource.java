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

package de.christianmahnke.iiif.fliiifenleger.source;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.Tiler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;

@AutoService(ImageSource.class)
@NoArgsConstructor
public class StackedImageSource implements ImageSource {
    private static final Logger log = LoggerFactory.getLogger(StackedImageSource.class);
    private static final String NAME = "stacked";

    private ImageSource finalSource;
    private int totalWidth = 0;
    private int totalHeight = 0;
    private URL url;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public BufferedImage getImage() throws ImageSourceException {
        // This could be very memory intensive. It's better to use crop.
        log.warn("Requesting the full composite image. This can be memory-intensive.");
        return finalSource.crop(0, 0, totalWidth, totalHeight, 1.0);
    }

    @Override
    public URL getUrl() {
        // The stacked source doesn't have a single URL. We can return the URL of the first image.
        return url;
    }

    @Override
    public void load(URL url)throws ImageSourceException{
        this.url = url;
        log.warn("setUrl() called on StackedImageSource, but it must be configured via setOptions(). Ignoring.");
    }

    @Override
    public int getWidth() {
        return totalWidth;
    }

    @Override
    public int getHeight() {
        return totalHeight;
    }

    @Override
    public BufferedImage crop(int x, int y, int width, int height, double scale) throws ImageSourceException {
        if (finalSource == null) {
            throw new IllegalStateException("StackedImageSource has not been configured. Call setOptions() first.");
        }
        return finalSource.crop(x, y, width, height, scale);
    }

    @Override
    public Map<String, Object> getMetadata() {
        // Return metadata from the final, fully-wrapped source in the chain.
        return finalSource != null ? finalSource.getMetadata() : new HashMap<>();
    }

    /**
     * Returns the final source in the processing chain.
     * This is package-private for testing purposes.
     */
    ImageSource getFinalSource() {
        return finalSource;
    }

    @Override
    public void setOptions(Map<String, String> options) {
        if (options == null) {
            return;
        }

        if (options.containsKey("config")) {
            String configPath = options.get("config");
            parseYamlConfig(configPath);
        } else {
            parseLegacyOptions(options);
        }
    }

    @SuppressWarnings("unchecked")
    private void parseLegacyOptions(Map<String, String> options) {
        // Expecting options like 'source.0=default:/path/to/image1.jpg', 'source.1=jxl:/path/to/image2.jxl'
        List<Map.Entry<String, String>> sourceDefs = options.entrySet().stream()
                .filter(e -> e.getKey().startsWith("source."))
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toList());

        if (sourceDefs.isEmpty()) {
            throw new IllegalArgumentException("StackedImageSource requires 'source.N' options.");
        }

        try {
            // The first source is the base ImageSource
            this.finalSource = createSourceFromString(sourceDefs.get(0).getValue(), null);

            // Subsequent sources are ManipulatorImageSources wrapping the previous one
            chainManipulators(sourceDefs.subList(1, sourceDefs.size()));
            finalizeConfiguration();
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure StackedImageSource from legacy options", e);
        }
    }

    /**
     * Parses a YAML configuration file to build the image source chain.
     * The first source in the list must be a base ImageSource with a path.
     * Subsequent sources must be ManipulatorImageSources.
     *
     * <p>Example YAML format:
     * <pre>{@code
     * sources:
     *   - type: default
     *     path: /path/to/base/image.jpg
     *   - type: filter
     *     options:
     *       type: sepia
     * }</pre>
     * @param configPath The path to the YAML configuration file.
     */
    private void parseYamlConfig(String configPath) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            File configFile = new File(configPath);
            Map<String, Object> config = mapper.readValue(configFile, Map.class);
    
            Object sourcesObj = config.get("sources");
            if (!(sourcesObj instanceof List)) {
                throw new IllegalArgumentException("YAML config must contain a 'sources' list.");
            }
    
            @SuppressWarnings("unchecked") // We've checked that it's a List
            List<Map<String, Object>> sourceConfigs = (List<Map<String, Object>>) sourcesObj;
            if (sourceConfigs.isEmpty()) {
                throw new IllegalArgumentException("YAML config 'sources' list cannot be empty.");
            }
    
            // First source is the base
            this.finalSource = createSourceFromConfig(sourceConfigs.get(0));

            // Subsequent sources are manipulators
            for (Map<String, Object> manipulatorConfig : sourceConfigs.subList(1, sourceConfigs.size())) {
                this.finalSource = createManipulatorFromConfig(manipulatorConfig, this.finalSource);
            }
            finalizeConfiguration();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read or parse YAML config file: " + configPath, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure StackedImageSource from YAML", e);
        }
    }

    private void chainManipulators(List<Map.Entry<String, String>> manipulatorDefs) throws Exception {
        for (Map.Entry<String, String> def : manipulatorDefs) {
            this.finalSource = createManipulatorFromString(def.getValue(), this.finalSource);
        }
    }

    private ImageSource createSourceFromString(String sourceDef, Map<String, String> options) throws Exception {
        String[] parts = sourceDef.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid source definition: " + sourceDef + ". Expected format 'type:/path/to/image'");
        }
        return createSource(parts[0], parts[1], options);
    }

    private ImageSource createSourceFromConfig(Map<String, Object> config) throws Exception {
        String type = (String) config.get("type");
        String path = (String) config.get("path");
        @SuppressWarnings("unchecked")
        Map<String, String> sourceOpts = ((Map<String, Object>) config.getOrDefault("options", new HashMap<>()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()));
        return createSource(type, path, sourceOpts);
    }

    private ImageSource createSource(String type, String path, Map<String, String> options) throws Exception {
        ImageSource sourceTemplate = Tiler.SOURCE_REGISTRY.get(type);
        if (sourceTemplate == null) {
            throw new IllegalArgumentException("Unknown source type '" + type + "'");
        }

        URL sourceUrl = new File(path).toURI().toURL();
        ImageSource instance = sourceTemplate.getClass().getConstructor().newInstance();
        instance.load(sourceUrl);

        if (options != null) {
            instance.setOptions(options);
        }
        return instance;
    }

    private ManipulatorImageSource createManipulatorFromString(String sourceDef, ImageSource baseSource) throws Exception {
        // Manipulators can be defined as just "type" or with options like "type:key1=value1,key2=value2".
        // The path part of a source definition is used for options here.
        String[] parts = sourceDef.split(":", 2);
        String type = parts[0];
        Map<String, String> manipulatorOptions = new HashMap<>();

        if (parts.length > 1 && parts[1] != null && !parts[1].isEmpty()) {
            String[] optionPairs = parts[1].split(",");
            for (String pair : optionPairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    manipulatorOptions.put(keyValue[0].trim(), keyValue[1].trim());
                }
            }
        }
        return createManipulator(type, manipulatorOptions, baseSource);
    }

    private ManipulatorImageSource createManipulatorFromConfig(Map<String, Object> config, ImageSource baseSource) throws Exception {
        String type = (String) config.get("type");
        @SuppressWarnings("unchecked")
        Map<String, String> sourceOpts = ((Map<String, Object>) config.getOrDefault("options", new HashMap<>()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()));
        return createManipulator(type, sourceOpts, baseSource);
    }

    private ManipulatorImageSource createManipulator(String type, Map<String, String> options, ImageSource baseSource) throws Exception {
        ImageSource sourceTemplate = Tiler.SOURCE_REGISTRY.get(type);
        if (sourceTemplate == null) throw new IllegalArgumentException("Unknown source type '" + type + "'");
        if (!(sourceTemplate instanceof ManipulatorImageSource)) throw new IllegalArgumentException("Source type '" + type + "' is not a ManipulatorImageSource.");

        ManipulatorImageSource instance = (ManipulatorImageSource) sourceTemplate.getClass().getConstructor().newInstance();
        instance.setOptions(options);
        instance.load(baseSource);
        return instance;
    }

    private void finalizeConfiguration() throws MalformedURLException {
        if (finalSource != null) {
            // Set a virtual URL for the stacked image, using the final source's path
            String firstImagePath = finalSource.getUrl().getPath();
            String stackedPath = firstImagePath.substring(0, firstImagePath.lastIndexOf('/') + 1) + "stacked-image.composite";
            this.url = new URL("file", "", stackedPath);
            calculateDimensions();
        } else {
            throw new IllegalArgumentException("StackedImageSource requires at least one source to be configured.");
        }
    }
    
    private void calculateDimensions() {
        if (finalSource == null) {
            totalWidth = 0;
            totalHeight = 0;
            return;
        }

        totalWidth = finalSource.getWidth();
        totalHeight = finalSource.getHeight();

        log.info("Chained source configured. Final dimensions: {}x{}", totalWidth, totalHeight);
    }
}