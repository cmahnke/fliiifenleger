// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import com.networknt.schema.Error;
import de.christianmahnke.iiif.fliiifenleger.validation.ValidationResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates IIIF Presentation API manifests (V2, V3, V4).
 *
 * <p>V3 and V4 are checked against the schemas bundled by
 * {@link PresentationSchemaRegistry}. V2 has no official JSON Schema, so it
 * is checked with structural rules only. All versions additionally get the
 * checks that JSON Schema cannot express (context order, unique IDs).
 */
public final class ManifestValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> REFERENCE_KEYS = Set.of(
            "target", "lookAt", "range", "structures", "first", "last", "start", "source", "body", "scope");

    private ManifestValidator() {
    }

    public static ValidationResult validate(Path manifest) throws IOException {
        return validate(Files.readString(manifest), null, null);
    }

    public static ValidationResult validate(Path manifest, ImageInfo.IIIFVersion expected) throws IOException {
        return validate(Files.readString(manifest), expected, null);
    }

    public static ValidationResult validate(URL manifest) throws IOException {
        try (InputStream in = manifest.openStream()) {
            return validate(new String(in.readAllBytes(), StandardCharsets.UTF_8), null, manifest.toString());
        }
    }

    public static ValidationResult validate(URL manifest, ImageInfo.IIIFVersion expected) throws IOException {
        try (InputStream in = manifest.openStream()) {
            return validate(new String(in.readAllBytes(), StandardCharsets.UTF_8), expected, manifest.toString());
        }
    }

    public static ValidationResult validate(String json) {
        return validate(json, null, null);
    }

    public static ValidationResult validate(String json, ImageInfo.IIIFVersion expected) {
        return validate(json, expected, null);
    }

    public static ValidationResult validate(String json, ImageInfo.IIIFVersion expected, String sourceUrl) {
        List<String> errors = new ArrayList<>();
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            return ValidationResult.failures(expected, List.of("manifest is not valid JSON: " + e.getMessage()));
        }
        if (!node.isObject()) {
            return ValidationResult.failures(expected, List.of("manifest must be a JSON object"));
        }

        ImageInfo.IIIFVersion detected = IiifManifest.detectVersion(node);
        ImageInfo.IIIFVersion effective = expected != null ? expected : detected;
        if (expected != null && expected != detected) {
            errors.add("Version mismatch: document looks like Presentation API "
                    + detected.getShortName() + " but " + expected.getShortName() + " was expected");
        }

        if (effective == ImageInfo.IIIFVersion.V2) {
            errors.addAll(checkV2(node));
        } else {
            for (Error violation : PresentationSchemaRegistry.schemaFor(effective).validate(node)) {
                String location = violation.getInstanceLocation() == null ? "" : violation.getInstanceLocation().toString();
                errors.add(location.isEmpty() ? violation.getMessage() : location + ": " + violation.getMessage());
            }
        }
        errors.addAll(checkContextOrder(node, effective));
        if (effective == ImageInfo.IIIFVersion.V4) {
            checkDuplicateIds(node, "", new HashSet<>(), errors);
        }
        if (sourceUrl != null && (effective == ImageInfo.IIIFVersion.V3 || effective == ImageInfo.IIIFVersion.V4)) {
            JsonNode id = node.get("id");
            if (id != null && !sourceUrl.equals(id.asString())) {
                errors.add("/id: manifest id (" + id.asString()
                        + ") should match the URL it was retrieved from (" + sourceUrl + ")");
            }
        }

        return errors.isEmpty() ? ValidationResult.ok(detected) : ValidationResult.failures(detected, errors);
    }

    private static List<String> checkContextOrder(JsonNode node, ImageInfo.IIIFVersion version) {
        List<String> errors = new ArrayList<>();
        if (version != ImageInfo.IIIFVersion.V4) return errors;
        JsonNode context = node.get("@context");
        String expected = IiifManifest.V4_CONTEXT;
        if (context == null || context.isString()) {
            if (context == null || !expected.equals(context.asString())) {
                errors.add("/@context: Presentation 4 manifests must use " + expected);
            }
            return errors;
        }
        if (!context.isArray() || context.isEmpty() || !expected.equals(context.get(context.size() - 1).asString())) {
            errors.add("/@context: the IIIF context must be the last entry for Presentation 4");
        }
        return errors;
    }

    private static List<String> checkV2(JsonNode node) {
        List<String> errors = new ArrayList<>();
        JsonNode context = node.get("@context");
        boolean hasV2Context = context != null && (context.isString() && context.asString().contains("presentation/2")
                || context.isArray() && context.toString().contains("presentation/2"));
        if (!hasV2Context) errors.add("/@context: expected IIIF Presentation 2 context");
        if (!"sc:Manifest".equals(text(node.get("@type")))) errors.add("/@type: expected sc:Manifest");
        if (!isHttpUri(text(node.get("@id")))) errors.add("/@id: required HTTP(S) URI");
        if (text(node.get("label")) == null && node.get("label") == null) {
            errors.add("/label: required");
        }
        JsonNode sequences = node.get("sequences");
        if (sequences == null || !sequences.isArray() || sequences.isEmpty()) {
            errors.add("/sequences: required non-empty array");
            return errors;
        }
        JsonNode canvases = sequences.get(0).get("canvases");
        if (canvases == null || !canvases.isArray() || canvases.isEmpty()) {
            errors.add("/sequences/0/canvases: required non-empty array");
            return errors;
        }
        for (int i = 0; i < canvases.size(); i++) {
            JsonNode canvas = canvases.get(i);
            String prefix = "/sequences/0/canvases/" + i;
            if (!isHttpUri(text(canvas.get("@id")))) errors.add(prefix + "/@id: required HTTP(S) URI");
            if (!"sc:Canvas".equals(text(canvas.get("@type")))) errors.add(prefix + "/@type: expected sc:Canvas");
            if (!canvas.has("width") || !canvas.has("height")) errors.add(prefix + ": width and height are required");
            JsonNode images = canvas.get("images");
            if (images == null || !images.isArray() || images.isEmpty()) {
                errors.add(prefix + "/images: required non-empty array");
                continue;
            }
            JsonNode resource = images.get(0).get("resource");
            if (resource == null || !isHttpUri(text(resource.get("@id")))) {
                errors.add(prefix + "/images/0/resource/@id: required HTTP(S) URI");
            }
            JsonNode service = resource == null ? null : resource.get("service");
            if (service == null || !"iiif:ImageService".equals(text(service.get("@type")))) {
                errors.add(prefix + "/images/0/resource/service/@type: expected iiif:ImageService");
            }
        }
        return errors;
    }

    private static void checkDuplicateIds(JsonNode node, String path, Set<String> ids, List<String> errors) {
        if (!node.isObject()) return;
        for (String key : node.propertyNames()) {
            JsonNode value = node.get(key);
            String childPath = path + "/" + key;
            if ("id".equals(key)) {
                if (!value.isString()) {
                    errors.add(childPath + ": id must be a string");
                } else if (!ids.add(value.asString())) {
                    errors.add(childPath + ": duplicate id " + value.asString());
                }
            } else if (!REFERENCE_KEYS.contains(key)) {
                if (value.isArray()) {
                    for (int i = 0; i < value.size(); i++) {
                        checkDuplicateIds(value.get(i), childPath + "[" + i + "]", ids, errors);
                    }
                } else if (value.isObject()) {
                    checkDuplicateIds(value, childPath, ids, errors);
                }
            }
        }
    }

    private static String text(JsonNode node) {
        return node == null || !node.isString() ? null : node.asString();
    }

    private static boolean isHttpUri(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }
}
