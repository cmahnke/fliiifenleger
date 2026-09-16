// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.validation;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.OptionDescriptor;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A pluggable {@code info.json} validator, analogous to
 * {@link de.christianmahnke.iiif.fliiifenleger.source.ImageSource} and
 * {@link de.christianmahnke.iiif.fliiifenleger.sink.TileSink}.
 *
 * <p>Implementations live in core (generic IIIF rules) and in the extension
 * modules (e.g. C2PA, UltraHDR service semantics) and are discovered via
 * {@link java.util.ServiceLoader} (register with
 * {@code @AutoService(Validator.class)}). The
 * {@link de.christianmahnke.iiif.fliiifenleger.InfoJsonValidator} facade runs
 * JSON Schema validation first and then every discovered validator whose
 * {@link #supports(ImageInfo.IIIFVersion)} accepts the effective version,
 * merging all {@link ValidationResult}s.
 *
 * <p>Contract: validators must be stateless and thread-safe and must only
 * report findings for documents they understand (return
 * {@link ValidationResult#ok(ImageInfo.IIIFVersion)} otherwise); invocation
 * order is unspecified.
 */
public interface Validator {

    /**
     * @return The unique name of this validator (e.g. {@code "core-context"},
     *         {@code "c2pa"}, {@code "hdr"}).
     */
    String getName();

    /**
     * @return Short human-readable description of this validator (used by
     *         {@code info list-validators}).
     */
    default String getDescription() {
        return "";
    }

    /**
     * Sets options for this validator.
     *
     * @param options A map of key-value pairs.
     */
    default void setOptions(Map<String, String> options) {
    }

    /**
     * Introspects the configuration options accepted by {@link #setOptions}.
     *
     * @return Descriptors with name and description (plus default, required flag and
     *         type hint); empty when this validator takes no options.
     */
    default List<OptionDescriptor> getAvailableOptions() {
        return List.of();
    }

    /**
     * Whether this validator applies to the given IIIF Image API version.
     * Validators returning {@code false} are skipped by the facade.
     *
     * @param version The effective version being validated.
     * @return {@code true} if this validator should run.
     */
    default boolean supports(ImageInfo.IIIFVersion version) {
        return true;
    }

    /**
     * Validates one parsed {@code info.json} document.
     *
     * @param node             The parsed document; never {@code null} and
     *                         always a JSON object.
     * @param effectiveVersion The IIIF Image API version being validated
     *                         against (explicit expectation or auto-detected).
     * @return This validator's slice of the result; {@code detectedVersion}
     *         may be {@code null} (the facade supplies its own detection).
     *         Never {@code null}.
     */
    ValidationResult validate(JsonNode node, ImageInfo.IIIFVersion effectiveVersion);

    /**
     * Loads all {@link Validator} implementations in ServiceLoader order.
     *
     * @return Unmodifiable list, possibly empty.
     */
    static List<Validator> loadAll() {
        List<Validator> validators = new ArrayList<>();
        java.util.ServiceLoader.load(Validator.class).forEach(validators::add);
        return Collections.unmodifiableList(validators);
    }
}
