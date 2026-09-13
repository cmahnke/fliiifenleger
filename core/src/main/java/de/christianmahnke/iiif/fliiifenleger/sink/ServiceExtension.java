// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.sink;

import java.util.Map;

/**
 * Describes one {@code info.json} service extension (e.g. C2PA provenance or
 * UltraHDR gain-map support) so the core {@code info.json} handling can
 * advertise and validate it without knowing the extension itself.
 *
 * <p>Implementations live in the extension modules and are discovered via
 * {@link java.util.ServiceLoader} (register with
 * {@code @AutoService(ServiceExtension.class)}). Core ships no
 * implementations: on a core-only classpath, extension service entries
 * validate as generic services.
 *
 * <p>Each implementation additionally ships a self-contained JSON Schema
 * fragment (see {@link #schemaResource()}) constraining only the service
 * entries that claim its {@link #profileUri()}; the validator composes all
 * discovered fragments with the base Image API schema via {@code allOf}.
 * Fragments apply to Image API 3 only — Image API 2 extensions are plain
 * URIs in the embedded profile {@code supports} list and need no schema.
 */
public interface ServiceExtension {

    /**
     * @return The service {@code profile} URI identifying this extension,
     *         e.g. {@code https://christianmahnke.de/iiif/c2pa/}.
     */
    String profileUri();

    /**
     * @return The JSON-LD context URI that must accompany the service entry
     *         in a V3 {@code @context} array, e.g.
     *         {@code https://christianmahnke.de/iiif/c2pa/context.json}.
     */
    String contextUri();

    /**
     * @return Classpath resource path of the JSON Schema fragment for this
     *         extension, loaded through the implementation class, e.g.
     *         {@code "/schema/c2pa-service.json"}.
     */
    String schemaResource();

    /**
     * Loads the schema fragment as a Jackson tree.
     *
     * @return The parsed fragment; never {@code null}.
     * @throws IllegalStateException if the resource cannot be read.
     */
    default tools.jackson.databind.JsonNode schemaFragment() {
        try (var in = getClass().getResourceAsStream(schemaResource())) {
            if (in == null) {
                throw new IllegalStateException("Extension schema not found: " + schemaResource());
            }
            return new tools.jackson.databind.ObjectMapper().readTree(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot load extension schema: " + schemaResource(), e);
        }
    }

    /**
     * Loads all {@link ServiceExtension} implementations in ServiceLoader order.
     *
     * @return Unmodifiable list, possibly empty.
     */
    static java.util.List<ServiceExtension> loadAll() {
        java.util.List<ServiceExtension> extensions = new java.util.ArrayList<>();
        java.util.ServiceLoader.load(ServiceExtension.class).forEach(extensions::add);
        return java.util.Collections.unmodifiableList(extensions);
    }
}
