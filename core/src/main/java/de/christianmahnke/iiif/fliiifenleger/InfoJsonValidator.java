// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import de.christianmahnke.iiif.fliiifenleger.validation.ValidationResult;
import de.christianmahnke.iiif.fliiifenleger.validation.Validator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>Beyond structural validation, semantic rules that JSON Schema expresses
 * poorly are checked by pluggable {@link Validator} implementations
 * (discovered via {@link java.util.ServiceLoader}): core ships the generic
 * IIIF rules, the extension modules own their service semantics (see
 * {@link de.christianmahnke.iiif.fliiifenleger.validation.Validator}).
 */
public final class InfoJsonValidator {

    public static final String V2_CONTEXT = "http://iiif.io/api/image/2/context.json";
    public static final String V3_CONTEXT = "http://iiif.io/api/image/3/context.json";

    /**
     * Discovered validators by name, mirroring {@code Tiler.SOURCE_REGISTRY}
     * / {@code SINK_REGISTRY} (see {@code info list-validators}).
     */
    public static final Map<String, Validator> VALIDATOR_REGISTRY = loadValidators();

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

    protected static Map<String, Validator> loadValidators() {
        Map<String, Validator> validators = new ConcurrentHashMap<>();
        Validator.loadAll().forEach(validator -> validators.put(validator.getName(), validator));
        return validators;
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
     * Runs every discovered {@link Validator} accepting the effective
     * version and merges their {@link ValidationResult}s.
     *
     * @param node    The parsed {@code info.json} document (a JSON object).
     * @param version The effective IIIF Image API version.
     * @return The merged validator errors (without version information; the
     *         caller supplies its own detection result).
     */
    protected static List<String> semanticChecks(JsonNode node, ImageInfo.IIIFVersion version) {
        List<String> errors = new ArrayList<>();
        for (Validator validator : VALIDATOR_REGISTRY.values()) {
            if (!validator.supports(version)) {
                continue;
            }
            try {
                ValidationResult result = validator.validate(node, version);
                if (result != null) {
                    errors.addAll(result.errors());
                }
            } catch (Exception e) {
                errors.add("validator '" + validator.getName() + "' failed: " + e.getMessage());
            }
        }
        return errors;
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
            if (context.isString()) {
                String ctx = context.asString();
                if (V3_CONTEXT.equals(ctx)) {
                    return ImageInfo.IIIFVersion.V3;
                }
                if (V2_CONTEXT.equals(ctx)) {
                    return ImageInfo.IIIFVersion.V2;
                }
            } else if (context.isArray()) {
                for (JsonNode entry : context) {
                    if (entry.isString()) {
                        if (V3_CONTEXT.equals(entry.asString())) {
                            return ImageInfo.IIIFVersion.V3;
                        }
                        if (V2_CONTEXT.equals(entry.asString())) {
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

    protected static Schema schemaFor(ImageInfo.IIIFVersion version) {
        return version == ImageInfo.IIIFVersion.V3 ? V3_SCHEMA : V2_SCHEMA;
    }

    protected static Schema loadComposed(String baseResource,
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

    protected static String textOrNull(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }
}
