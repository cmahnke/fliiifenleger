// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collection;

public class IiifManifest {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String V2_CONTEXT = "http://iiif.io/api/presentation/2/context.json";
    public static final String V3_CONTEXT = "https://www.w3.org/ns/iiif/presentation/3/context.json";

    protected final ImageInfo.IIIFVersion version;
    protected String baseUri;
    protected String label;
    protected String description;
    protected final List<CanvasRef> canvases = new ArrayList<>();

    public IiifManifest(ImageInfo.IIIFVersion version, String baseUri) {
        this.version = version;
        this.baseUri = baseUri;
    }

    public IiifManifest(ImageInfo.IIIFVersion version, String baseUri, String label) {
        this.version = version;
        this.baseUri = baseUri;
        this.label = label;
    }

    public ImageInfo.IIIFVersion getVersion() { return version; }
    public String getBaseUri() { return baseUri; }
    public void setBaseUri(String baseUri) { this.baseUri = baseUri; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<CanvasRef> getCanvases() { return canvases; }

    public void addCanvas(String canvasId, String label, String imageInfoId, int width, int height) {
        canvases.add(new CanvasRef(canvasId, label, imageInfoId, width, height));
    }

    public JsonNode toJson() {
        return version == ImageInfo.IIIFVersion.V2 ? buildV2() : buildV3();
    }

    protected ObjectNode buildV2() {
        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("@context", V2_CONTEXT);
        manifest.put("@id", baseUri);
        manifest.put("@type", "sc:Manifest");
        if (label != null) manifest.put("label", label);
        if (description != null) manifest.put("description", description);

        ObjectNode sequence = MAPPER.createObjectNode();
        sequence.put("@id", baseUri + "/sequence/normal");
        sequence.put("@type", "sc:Sequence");

        ArrayNode canvasesArray = MAPPER.createArrayNode();
        for (CanvasRef canvas : canvases) {
            canvasesArray.add(buildCanvasV2(canvas));
        }
        sequence.set("canvases", canvasesArray);

        ArrayNode sequencesArray = MAPPER.createArrayNode();
        sequencesArray.add(sequence);
        manifest.set("sequences", sequencesArray);

        return manifest;
    }

    protected ObjectNode buildCanvasV2(CanvasRef canvas) {
        ObjectNode canvasNode = MAPPER.createObjectNode();
        canvasNode.put("@id", baseUri + "/canvas/" + canvas.id);
        canvasNode.put("@type", "sc:Canvas");
        if (canvas.label != null) canvasNode.put("label", canvas.label);
        canvasNode.put("width", canvas.width);
        canvasNode.put("height", canvas.height);

        ObjectNode annotation = MAPPER.createObjectNode();
        annotation.put("@id", baseUri + "/canvas/" + canvas.id + "/annotation");
        annotation.put("@type", "oa:Annotation");
        annotation.put("motivation", "sc:painting");

        ObjectNode resource = MAPPER.createObjectNode();
        resource.put("@id", canvas.imageInfoId);
        resource.put("@type", "dctypes:Image");
        resource.put("format", "image/jpeg");
        resource.put("width", canvas.width);
        resource.put("height", canvas.height);

        ObjectNode service = MAPPER.createObjectNode();
        service.put("@id", canvas.imageInfoId);
        service.put("@type", "iiif:ImageService");
        service.put("profile", "http://iiif.io/api/image/2/level2.json");
        resource.set("service", service);

        annotation.set("resource", resource);

        ObjectNode onTarget = MAPPER.createObjectNode();
        onTarget.put("@id", baseUri + "/canvas/" + canvas.id + "/full");
        annotation.set("on", onTarget);

        ArrayNode imagesArray = MAPPER.createArrayNode();
        imagesArray.add(annotation);
        canvasNode.set("images", imagesArray);

        if (canvas.seeAlso != null && !canvas.seeAlso.isEmpty()) {
            ArrayNode seeAlsoArray = MAPPER.createArrayNode();
            for (SeeAlsoRef ref : canvas.seeAlso) {
                ObjectNode seeAlsoNode = MAPPER.createObjectNode();
                seeAlsoNode.put("id", ref.id);
                if (ref.type != null) {
                    seeAlsoNode.put("type", ref.type);
                }
                if (ref.format != null) {
                    seeAlsoNode.put("format", ref.format);
                }
                if (ref.profile != null) {
                    seeAlsoNode.put("profile", ref.profile);
                }
                seeAlsoArray.add(seeAlsoNode);
            }
            canvasNode.set("seeAlso", seeAlsoArray);
        }

        return canvasNode;
    }

    protected ObjectNode buildV3() {
        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("@context", V3_CONTEXT);
        manifest.put("id", baseUri);
        manifest.put("type", "Manifest");
        if (label != null) manifest.put("label", label);
        if (description != null) manifest.put("description", description);

        ArrayNode itemsArray = MAPPER.createArrayNode();
        for (CanvasRef canvas : canvases) {
            itemsArray.add(buildCanvasV3(canvas));
        }
        manifest.set("items", itemsArray);

        return manifest;
    }

    protected ObjectNode buildCanvasV3(CanvasRef canvas) {
        ObjectNode canvasNode = MAPPER.createObjectNode();
        canvasNode.put("id", baseUri + "/canvas/" + canvas.id);
        canvasNode.put("type", "Canvas");
        if (canvas.label != null) canvasNode.put("label", canvas.label);
        canvasNode.put("width", canvas.width);
        canvasNode.put("height", canvas.height);

        ObjectNode item = MAPPER.createObjectNode();
        item.put("id", canvas.imageInfoId);
        item.put("type", "Image");
        item.put("width", canvas.width);
        item.put("height", canvas.height);

        ArrayNode bodyArray = MAPPER.createArrayNode();
        bodyArray.add(item);
        canvasNode.set("body", bodyArray);

        ObjectNode target = MAPPER.createObjectNode();
        target.put("id", baseUri + "/canvas/" + canvas.id);
        target.put("type", "Canvas");
        canvasNode.set("target", target);

        ArrayNode itemsFinal = MAPPER.createArrayNode();
        itemsFinal.add(item);
        canvasNode.set("items", itemsFinal);

        // Reset to cleaner structure
        canvasNode.remove("body");
        canvasNode.remove("target");

        if (canvas.seeAlso != null && !canvas.seeAlso.isEmpty()) {
            ArrayNode seeAlsoArray = MAPPER.createArrayNode();
            for (SeeAlsoRef ref : canvas.seeAlso) {
                ObjectNode seeAlsoNode = MAPPER.createObjectNode();
                seeAlsoNode.put("id", ref.id);
                if (ref.type != null) {
                    seeAlsoNode.put("type", ref.type);
                }
                if (ref.format != null) {
                    seeAlsoNode.put("format", ref.format);
                }
                if (ref.profile != null) {
                    seeAlsoNode.put("profile", ref.profile);
                }
                seeAlsoArray.add(seeAlsoNode);
            }
            canvasNode.set("seeAlso", seeAlsoArray);
        }

        return canvasNode;
    }

    public static IiifManifest fromJson(String json) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        ImageInfo.IIIFVersion version = detectVersion(node);
        String id = version == ImageInfo.IIIFVersion.V2
                ? (node.has("@id") ? node.get("@id").asString() : null)
                : (node.has("id") ? node.get("id").asString() : null);
        String label = node.has("label") ? node.get("label").asString() : null;
        String description = node.has("description") ? node.get("description").asString() : null;

        IiifManifest manifest = new IiifManifest(version, id, label);
        if (description != null) manifest.setDescription(description);

        List<CanvasRef> canvasRefs = extractCanvases(node, version);
        manifest.getCanvases().addAll(canvasRefs);

        return manifest;
    }

    protected static ImageInfo.IIIFVersion detectVersion(JsonNode node) {
        JsonNode context = node.get("@context");
        if (context != null) {
            String ctxStr = context.isString() ? context.asString() : context.get(0).asString();
            if (ctxStr != null && ctxStr.contains("presentation/3")) return ImageInfo.IIIFVersion.V3;
        }
        if (node.has("type") && "Manifest".equals(node.get("type").asString())) return ImageInfo.IIIFVersion.V3;
        return ImageInfo.IIIFVersion.V2;
    }

    protected static List<CanvasRef> extractCanvases(JsonNode node, ImageInfo.IIIFVersion version) {
        List<CanvasRef> refs = new ArrayList<>();
        if (version == ImageInfo.IIIFVersion.V2) {
            JsonNode sequences = node.get("sequences");
            if (sequences != null && sequences.isArray() && sequences.size() > 0) {
                JsonNode canvases = sequences.get(0).get("canvases");
                if (canvases != null && canvases.isArray()) {
                    for (JsonNode canvasNode : canvases) {
                        String canvasId = canvasNode.has("@id") ? canvasNode.get("@id").asString() : null;
                        String canvasLabel = canvasNode.has("label") ? canvasNode.get("label").asString() : null;
                        int width = canvasNode.has("width") ? canvasNode.get("width").asInt() : 0;
                        int height = canvasNode.has("height") ? canvasNode.get("height").asInt() : 0;
                        String imageInfoId = null;
                        if (canvasNode.has("images") && canvasNode.get("images").isArray()
                                && canvasNode.get("images").size() > 0) {
                            JsonNode images = canvasNode.get("images").get(0);
                            if (images != null && images.has("resource")) {
                                imageInfoId = images.get("resource").get("@id").asString();
                            }
                        }
                        List<SeeAlsoRef> seeAlsoRefs = extractSeeAlso(canvasNode);
                        if (canvasId != null) {
                            String id = canvasId.substring(canvasId.lastIndexOf('/') + 1);
                            refs.add(new CanvasRef(id, canvasLabel, imageInfoId, width, height, null, seeAlsoRefs));
                        }
                    }
                }
            }
        } else {
            JsonNode items = node.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode canvasNode : items) {
                    String canvasId = canvasNode.has("id") ? canvasNode.get("id").asString() : null;
                    String canvasLabel = canvasNode.has("label") ? canvasNode.get("label").asString() : null;
                    int width = canvasNode.has("width") ? canvasNode.get("width").asInt() : 0;
                    int height = canvasNode.has("height") ? canvasNode.get("height").asInt() : 0;
                    String imageInfoId = null;
                    if (canvasNode.has("items") && canvasNode.get("items").isArray()
                            && canvasNode.get("items").size() > 0) {
                        imageInfoId = canvasNode.get("items").get(0).get("id").asString();
                    }
                    List<SeeAlsoRef> seeAlsoRefs = extractSeeAlso(canvasNode);
                    if (canvasId != null) {
                        String id = canvasId.substring(canvasId.lastIndexOf('/') + 1);
                        refs.add(new CanvasRef(id, canvasLabel, imageInfoId, width, height, null, seeAlsoRefs));
                    }
                }
            }
        }
        return refs;
    }

    protected static List<SeeAlsoRef> extractSeeAlso(JsonNode canvasNode) {
        List<SeeAlsoRef> seeAlsoRefs = new ArrayList<>();
        JsonNode seeAlsoNode = canvasNode.get("seeAlso");
        if (seeAlsoNode != null && seeAlsoNode.isArray()) {
            for (JsonNode ref : seeAlsoNode) {
                String refId = ref.has("id") ? ref.get("id").asString() : null;
                String refType = ref.has("type") ? ref.get("type").asString() : null;
                String refFormat = ref.has("format") ? ref.get("format").asString() : null;
                String refProfile = ref.has("profile") ? ref.get("profile").asString() : null;
                if (refId != null) {
                    seeAlsoRefs.add(new SeeAlsoRef(refId, refType, refFormat, refProfile));
                }
            }
        }
        return seeAlsoRefs;
    }

    /**
     * Replaces all string values starting with oldBase with newBase in the JSON tree.
     * Returns the modified JSON as a pretty-printed string.
     */
    public static String replaceBaseUriInJson(String json, String oldBase, String newBase) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        ObjectNode output = (ObjectNode) replaceBaseUri(node, oldBase, newBase);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output);
    }

    protected static JsonNode replaceBaseUri(JsonNode node, String oldBase, String newBase) {
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            Collection<String> propNames = node.propertyNames();
            for (String key : propNames) {
                result.set(key, replaceBaseUri(node.get(key), oldBase, newBase));
            }
            return result;
        } else if (node.isArray()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (int i = 0; i < node.size(); i++) {
                arr.add(replaceBaseUri(node.get(i), oldBase, newBase));
            }
            return arr;
        } else if (node.isString()) {
            String text = node.asString();
            if (text.startsWith(oldBase) && !oldBase.equals(newBase)) {
                return MAPPER.getNodeFactory().stringNode(text.replace(oldBase, newBase));
            }
            return node;
        }
        return node;
    }

    public static String toPrettyJson(JsonNode node) throws Exception {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    public static class CanvasRef {
        public final String id;
        public final String label;
        public final String imageInfoId;
        public final int width;
        public final int height;
        public final Map<String, Object> metadata;
        public final List<SeeAlsoRef> seeAlso;

        public CanvasRef(String id, String label, String imageInfoId, int width, int height) {
            this(id, label, imageInfoId, width, height, null, new ArrayList<>());
        }
        public CanvasRef(String id, String label, String imageInfoId, int width, int height, Map<String, Object> metadata) {
            this(id, label, imageInfoId, width, height, metadata, new ArrayList<>());
        }
        public CanvasRef(String id, String label, String imageInfoId, int width, int height, Map<String, Object> metadata, List<SeeAlsoRef> seeAlso) {
            this.id = id;
            this.label = label;
            this.imageInfoId = imageInfoId;
            this.width = width;
            this.height = height;
            this.metadata = metadata;
            this.seeAlso = seeAlso != null ? seeAlso : new ArrayList<>();
        }

        public void addSeeAlso(SeeAlsoRef ref) {
            this.seeAlso.add(ref);
        }
    }

    public static class SeeAlsoRef {
        public final String id;
        public final String type;
        public final String format;
        public final String profile;

        public SeeAlsoRef(String id, String type, String format, String profile) {
            this.id = id;
            this.type = type;
            this.format = format;
            this.profile = profile;
        }
    }
}
