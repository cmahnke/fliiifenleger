// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ManifestMerger {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected final ImageInfo.IIIFVersion version;
    protected String baseUri;
    protected final List<ManifestData> manifests = new ArrayList<>();

    public ManifestMerger(ImageInfo.IIIFVersion version, String baseUri) {
        this.version = version;
        this.baseUri = baseUri;
    }

    public void addManifest(String json) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        manifests.add(new ManifestData(node));
    }

    public void addManifest(Path manifestPath) throws Exception {
        try (InputStream is = Files.newInputStream(manifestPath)) {
            manifests.add(new ManifestData(MAPPER.readTree(is)));
        }
    }

    public void addManifest(URL manifestUrl) throws Exception {
        try (InputStream is = manifestUrl.openStream()) {
            manifests.add(new ManifestData(MAPPER.readTree(is)));
        }
    }

    public JsonNode merge() throws Exception {
        ImageInfo.IIIFVersion effectiveVersion = determineVersion();
        List<String> labels = new ArrayList<>();
        List<IiifManifest.CanvasRef> allCanvases = new ArrayList<>();
        List<String> oldBaseUris = new ArrayList<>();

        for (ManifestData data : manifests) {
            IiifManifest manifest = IiifManifest.fromJson(MAPPER.writeValueAsString(data.node));
            labels.add(manifest.getLabel() != null ? manifest.getLabel() : "");
            allCanvases.addAll(manifest.getCanvases());
            oldBaseUris.add(data.id);
        }

        IiifManifest merged = new IiifManifest(effectiveVersion, baseUri);
        merged.setLabel(String.join(" + ", labels));
        merged.getCanvases().addAll(allCanvases);

        ObjectNode result = (ObjectNode) merged.toJson();

        for (String oldBase : oldBaseUris) {
            if (oldBase != null) {
                JsonNode replaced = replaceBaseUriValue(result, oldBase, baseUri);
                if (replaced.isObject()) {
                    result = (ObjectNode) replaced;
                }
            }
        }

        return result;
    }

    public String mergeToJson() throws Exception {
        return IiifManifest.toPrettyJson(merge());
    }

    public List<IiifManifest.CanvasRef> getCanvases() {
        List<IiifManifest.CanvasRef> all = new ArrayList<>();
        for (ManifestData data : manifests) {
            try {
                IiifManifest manifest = IiifManifest.fromJson(MAPPER.writeValueAsString(data.node));
                all.addAll(manifest.getCanvases());
            } catch (Exception e) { /* skip */ }
        }
        return all;
    }

    public int getManifestCount() {
        return manifests.size();
    }

    protected ImageInfo.IIIFVersion determineVersion() {
        if (!manifests.isEmpty()) {
            try {
                IiifManifest first = IiifManifest.fromJson(MAPPER.writeValueAsString(manifests.get(0).node));
                return first.getVersion();
            } catch (Exception e) { /* fall through */ }
        }
        return version;
    }

    protected static JsonNode replaceBaseUriValue(JsonNode node, String oldBase, String newBase) {
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            for (String key : node.propertyNames()) {
                result.set(key, replaceBaseUriValue(node.get(key), oldBase, newBase));
            }
            return result;
        } else if (node.isArray()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (int i = 0; i < node.size(); i++) {
                arr.add(replaceBaseUriValue(node.get(i), oldBase, newBase));
            }
            return arr;
        } else if (node.isTextual()) {
            String text = node.asText();
            if (text.startsWith(oldBase) && !oldBase.equals(newBase)) {
                return MAPPER.getNodeFactory().stringNode(text.replace(oldBase, newBase));
            }
            return node;
        }
        return node;
    }

    public static String mergeManifests(List<String> manifestJsons, ImageInfo.IIIFVersion version, String baseUri) throws Exception {
        ManifestMerger merger = new ManifestMerger(version, baseUri);
        for (String json : manifestJsons) {
            merger.addManifest(json);
        }
        return merger.mergeToJson();
    }

    public static String mergeManifestsFromPaths(List<Path> manifestPaths, ImageInfo.IIIFVersion version, String baseUri) throws Exception {
        ManifestMerger merger = new ManifestMerger(version, baseUri);
        for (Path path : manifestPaths) {
            merger.addManifest(path);
        }
        return merger.mergeToJson();
    }

    public static String mergeManifestsFromUrls(List<String> manifestJsons, ImageInfo.IIIFVersion version, String baseUri) throws Exception {
        ManifestMerger merger = new ManifestMerger(version, baseUri);
        for (String json : manifestJsons) {
            merger.addManifest(json);
        }
        return merger.mergeToJson();
    }

    public static void mergeAndSave(List<String> inputs, ImageInfo.IIIFVersion version, String baseUri, Path output) throws Exception {
        ManifestMerger merger = new ManifestMerger(version, baseUri);
        for (String input : inputs) {
            try {
                merger.addManifest(new URI(input).toURL());
            } catch (Exception e) {
                try {
                    merger.addManifest(Path.of(input));
                } catch (Exception e2) {
                    throw new Exception("Error reading manifest input: " + input, e2);
                }
            }
        }
        String result = merger.mergeToJson();
        Files.writeString(output, result);
    }

    private static class ManifestData {
        final JsonNode node;
        final String id;

        ManifestData(JsonNode node) throws Exception {
            this.node = node;
            this.id = node.has("@id") ? node.get("@id").asText()
                    : (node.has("id") ? node.get("id").asText() : null);
        }
    }
}
