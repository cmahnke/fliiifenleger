// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates {@code info.json} documents against the bundled JSON Schemas for
 * IIIF Image API 2 and 3.
 *
 * <p>Base schemas live in {@code core/src/main/resources/schema/}:
 * {@code image-api-2-info.json} and {@code image-api-3-info.json}. Service
 * extensions (C2PA, HDR, …) ship their own schema fragments in their modules
 * (see {@link de.christianmahnke.iiif.fliiifenleger.sink.ServiceExtension})
 * which are composed with the V3 base schema via {@code allOf}; the V2
 * schema models extensions as plain URIs in the embedded profile
 * {@code supports} list and rejects namespaced properties such as
 * {@code trustAnchor}. On a core-only classpath (no extensions discovered)
 * extension service entries validate as generic services.
 *
 * <p>Beyond structural validation, IIIF-specific ordering rules that JSON
 * Schema expresses poorly are checked in Java: a V3 {@code @context} array
 * must end with the IIIF context, and advertised extension services require
 * their context to be present.
 */
public final class InfoJsonValidator {

    public static final String V2_CONTEXT = "http://iiif.io/api/image/2/context.json";
    public static final String V3_CONTEXT = "http://iiif.io/api/image/3/context.json";

    private static final String V2_SCHEMA_RESOURCE = "/schema/image-api-2-info.json";
    private static final String V3_SCHEMA_RESOURCE = "/schema/image-api-3-info.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Discovered service extensions (C2PA, HDR, …); empty on a core-only classpath. */
    private static final List<de.christianmahnke.iiif.fliiifenleger.sink.ServiceExtension> EXTENSIONS =
            de.christianmahnke.iiif.fliiifenleger.sink.ServiceExtension.loadAll();

    private static final Schema V2_SCHEMA = loadComposed(V2_SCHEMA_RESOURCE, List.of());
    private static final Schema V3_SCHEMA = loadComposed(V3_SCHEMA_RESOURCE, EXTENSIONS);

    private InfoJsonValidator() {
    }

    public record ValidationResult(boolean valid, List<String> errors, ImageInfo.IIIFVersion detectedVersion) {
    }

    public static ValidationResult validate(Path infoJson) throws IOException {
        return validate(Files.readString(infoJson), null);
    }

    public static ValidationResult validate(Path infoJson, ImageInfo.IIIFVersion expected) throws IOException {
        return validate(Files.readString(infoJson), expected);
    }

    public static ValidationResult validate(URL infoJson) throws IOException {
        try (InputStream in = infoJson.openStream()) {
            return validate(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), null);
        }
    }

    public static ValidationResult validate(URL infoJson, ImageInfo.IIIFVersion expected) throws IOException {
        try (InputStream in = infoJson.openStream()) {
            return validate(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), expected);
        }
    }

    public static ValidationResult validate(String json) {
        return validate(json, null);
    }

    public static ValidationResult validate(String json, ImageInfo.IIIFVersion expected) {
        List<String> errors = new ArrayList<>();
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            return new ValidationResult(false, List.of("info.json is not valid JSON: " + e.getMessage()), null);
        }
        if (!node.isObject()) {
            return new ValidationResult(false, List.of("info.json must be a JSON object"), null);
        }

        ImageInfo.IIIFVersion detected = detectVersion(node);
        if (detected == null) {
            errors.add("Cannot detect IIIF Image API version: expected @context "
                    + V2_CONTEXT + " (V2, string) or " + V3_CONTEXT + " (V3, string or array ending with it)");
            return new ValidationResult(false, errors, null);
        }
        ImageInfo.IIIFVersion effective = expected != null ? expected : detected;
        if (expected != null && expected != detected) {
            errors.add("Version mismatch: document looks like Image API " + detected.getShortName()
                    + " but " + expected.getShortName() + " was expected");
        }

        Schema schema = schemaFor(effective);
        List<Error> violations = schema.validate(node);
        for (Error v : violations) {
            String location = v.getInstanceLocation() == null ? "" : v.getInstanceLocation().toString();
            errors.add(location.isEmpty() ? v.getMessage() : location + ": " + v.getMessage());
        }

        errors.addAll(semanticChecks(node, effective));

        return new ValidationResult(errors.isEmpty(), List.copyOf(errors), detected);
    }

    /**
     * Detects the IIIF Image API major version from {@code @context} (and, as
     * a fallback, {@code @id} vs {@code id}/{@code type}).
     *
     * @return V2, V3, or {@code null} when undetectable.
     */
    public static ImageInfo.IIIFVersion detectVersion(JsonNode node) {
        JsonNode context = node.get("@context");
        if (context != null) {
            if (context.isTextual()) {
                String ctx = context.asText();
                if (V3_CONTEXT.equals(ctx)) {
                    return ImageInfo.IIIFVersion.V3;
                }
                if (V2_CONTEXT.equals(ctx)) {
                    return ImageInfo.IIIFVersion.V2;
                }
            } else if (context.isArray()) {
                for (JsonNode entry : context) {
                    if (entry.isTextual()) {
                        if (V3_CONTEXT.equals(entry.asText())) {
                            return ImageInfo.IIIFVersion.V3;
                        }
                        if (V2_CONTEXT.equals(entry.asText())) {
                            return ImageInfo.IIIFVersion.V2;
                        }
                    }
                }
            }
        }
        // Fallback on key shapes.
        boolean hasAtId = node.has("@id");
        boolean hasId = node.has("id") && "ImageService3".equals(textOrNull(node.get("type")));
        if (hasId && !hasAtId) {
            return ImageInfo.IIIFVersion.V3;
        }
        if (hasAtId && !hasId) {
            return ImageInfo.IIIFVersion.V2;
        }
        return null;
    }

    private static List<String> semanticChecks(JsonNode node, ImageInfo.IIIFVersion version) {
        List<String> errors = new ArrayList<>();
        if (version == ImageInfo.IIIFVersion.V3) {
            JsonNode context = node.get("@context");
            if (context != null && context.isArray() && !context.isEmpty()) {
                JsonNode last = context.get(context.size() - 1);
                if (!last.isTextual() || !V3_CONTEXT.equals(last.asText())) {
                    errors.add("@context: the IIIF Image API 3 context must be the last entry of the array");
                }
            }
            List<String> contexts = new ArrayList<>();
            if (context != null) {
                if (context.isTextual()) {
                    contexts.add(context.asText());
                } else if (context.isArray()) {
                    for (JsonNode entry : context) {
                        if (entry.isTextual()) {
                            contexts.add(entry.asText());
                        }
                    }
                }
            }
            JsonNode service = node.get("service");
            if (service != null && service.isArray()) {
                for (JsonNode entry : service) {
                    String profile = textOrNull(entry.get("profile"));
                    for (var extension : EXTENSIONS) {
                        if (extension.profileUri().equals(profile)
                                && !contexts.contains(extension.contextUri())) {
                            errors.add("service: service '" + profile + "' requires @context to contain "
                                    + extension.contextUri());
                        }
                    }
                    JsonNode anchor = entry.get("trustAnchor");
                    if (anchor != null && (!anchor.isTextual() || !isAbsoluteUri(anchor.asText()))) {
                        errors.add("service: trustAnchor must be an absolute URI");
                    }
                }
            }
        } else {
            if (node.has("trustAnchor") || findKey(node, "trustAnchor")) {
                errors.add("Image API 2 must not contain the namespaced 'trustAnchor' property "
                        + "(fixed @context); use Image API 3");
            }
            JsonNode profile = node.get("profile");
            if (profile != null && profile.isArray() && !profile.isEmpty()) {
                JsonNode first = profile.get(0);
                if (!first.isTextual() || !first.asText().matches("^http://iiif\\.io/api/image/2/level[0-2]\\.json$")) {
                    errors.add("profile: first entry must be the Image API 2 compliance level URI");
                }
            }
        }
        return errors;
    }

    private static Schema schemaFor(ImageInfo.IIIFVersion version) {
        return version == ImageInfo.IIIFVersion.V3 ? V3_SCHEMA : V2_SCHEMA;
    }

    private static Schema loadComposed(String baseResource,
                                       List<de.christianmahnke.iiif.fliiifenleger.sink.ServiceExtension> extensions) {
        JsonNode base;
        try (InputStream in = InfoJsonValidator.class.getResourceAsStream(baseResource)) {
            if (in == null) {
                throw new IllegalStateException("Bundled schema not found: " + baseResource);
            }
            base = MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load bundled schema: " + baseResource, e);
        }
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        if (extensions.isEmpty()) {
            return registry.getSchema(base);
        }
        var allOf = MAPPER.createArrayNode();
        allOf.add(base);
        for (var extension : extensions) {
            allOf.add(extension.schemaFragment());
        }
        var composed = MAPPER.createObjectNode();
        composed.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        composed.set("allOf", allOf);
        return registry.getSchema(composed);
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static boolean isAbsoluteUri(String value) {
        try {
            return new java.net.URI(value).isAbsolute();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean findKey(JsonNode node, String key) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            if (node.has(key)) {
                return true;
            }
            for (var field : node.properties()) {
                if (findKey(field.getValue(), key)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode entry : node) {
                if (findKey(entry, key)) {
                    return true;
                }
            }
        }
        return false;
    }
}
