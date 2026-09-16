// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import java.util.List;

/**
 * Describes a single configuration option accepted via
 * {@code --source-opt} / {@code --sink-opt} (see
 * {@link de.christianmahnke.iiif.fliiifenleger.source.ImageSource#setOptions} and
 * {@link de.christianmahnke.iiif.fliiifenleger.sink.TileSink#setOptions}).
 *
 * <p>Returned by {@code getAvailableOptions()} for CLI introspection
 * ({@code info list-sources --verbose}, {@code info describe-source},
 * {@code info describe-sink}).
 *
 * @param name         Option key as used on the command line (e.g. {@code "format"}).
 * @param description  Human-readable description of the option.
 * @param defaultValue Default value when the option is unset (empty string when none).
 * @param required     Whether the option must be set.
 * @param type         Type hint (e.g. {@code "string"}, {@code "int"}, {@code "uri"}, {@code "path"}).
 */
public record OptionDescriptor(
        String name,
        String description,
        String defaultValue,
        boolean required,
        String type) {

    public OptionDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("OptionDescriptor name must not be blank");
        }
        description = description == null ? "" : description;
        defaultValue = defaultValue == null ? "" : defaultValue;
        type = type == null || type.isBlank() ? "string" : type;
    }

    /**
     * Convenience factory for optional string options.
     */
    public static OptionDescriptor optional(String name, String description, String defaultValue) {
        return new OptionDescriptor(name, description, defaultValue, false, "string");
    }

    /**
     * Convenience factory for optional options with an explicit type hint.
     */
    public static OptionDescriptor optional(String name, String description, String defaultValue, String type) {
        return new OptionDescriptor(name, description, defaultValue, false, type);
    }

    /**
     * Convenience factory for required options.
     */
    public static OptionDescriptor required(String name, String description, String type) {
        return new OptionDescriptor(name, description, "", true, type);
    }

    /**
     * Filters a list of descriptors to the required ones.
     */
    public static List<OptionDescriptor> requiredOnly(List<OptionDescriptor> options) {
        return options == null ? List.of() : options.stream().filter(OptionDescriptor::required).toList();
    }
}
