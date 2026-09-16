// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.validation;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared JSON helpers for {@link Validator} implementations (core and
 * extension modules).
 */
public final class ValidationSupport {

    private ValidationSupport() {
    }

    /**
     * @return The string value of the node, or {@code null} when absent or
     *         not a string.
     */
    public static String textOrNull(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    /**
     * @return Whether the value parses as an absolute URI.
     */
    public static boolean isAbsoluteUri(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return new java.net.URI(value).isAbsolute();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Collects the {@code @context} entries of the document as strings.
     *
     * @param node The parsed {@code info.json} document.
     * @return The context URIs (single string or array entries); never
     *         {@code null}.
     */
    public static List<String> collectContexts(JsonNode node) {
        List<String> contexts = new ArrayList<>();
        JsonNode context = node.get("@context");
        if (context == null) {
            return contexts;
        }
        if (context.isString()) {
            contexts.add(context.asString());
        } else if (context.isArray()) {
            for (JsonNode entry : context) {
                if (entry.isString()) {
                    contexts.add(entry.asString());
                }
            }
        }
        return contexts;
    }

    /**
     * Collects the {@code service} entries of the document.
     *
     * @param node The parsed {@code info.json} document.
     * @return The service entries (array form only, mirroring the previous
     *         facade behavior); never {@code null}.
     */
    public static List<JsonNode> serviceEntries(JsonNode node) {
        List<JsonNode> entries = new ArrayList<>();
        JsonNode service = node.get("service");
        if (service != null && service.isArray()) {
            for (JsonNode entry : service) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * @return Whether the document tree contains the key anywhere.
     */
    public static boolean findKey(JsonNode node, String key) {
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
