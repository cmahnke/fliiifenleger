// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.validation;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of validating one {@code info.json} document.
 *
 * <p>Returned by every {@link Validator} and by the composing facade
 * ({@link de.christianmahnke.iiif.fliiifenleger.InfoJsonValidator}).
 *
 * @param valid           Whether the document passed this validation step.
 * @param errors          Human-readable error messages (empty when valid).
 * @param detectedVersion The detected IIIF Image API version; individual
 *                        validators leave this {@code null} and let the
 *                        facade fill in its own detection result on merge.
 */
public record ValidationResult(boolean valid, List<String> errors, ImageInfo.IIIFVersion detectedVersion) {

    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /**
     * @return A passing result.
     */
    public static ValidationResult ok(ImageInfo.IIIFVersion detectedVersion) {
        return new ValidationResult(true, List.of(), detectedVersion);
    }

    /**
     * @return A failing result.
     */
    public static ValidationResult failures(ImageInfo.IIIFVersion detectedVersion, List<String> errors) {
        return new ValidationResult(false, errors, detectedVersion);
    }

    /**
     * @return A failing result without version information (for use by
     *         {@link Validator} implementations; the facade supplies the
     *         detected version when merging).
     */
    public static ValidationResult failures(List<String> errors) {
        return new ValidationResult(false, errors, null);
    }

    /**
     * Merges two results: valid only if both are valid, errors concatenated.
     * The first non-{@code null} detected version wins (i.e. the facade's
     * own detection result takes precedence over validator slices).
     *
     * @param other The other result; {@code null} is tolerated.
     * @return The merged result.
     */
    public ValidationResult merge(ValidationResult other) {
        if (other == null) {
            return this;
        }
        List<String> merged = new ArrayList<>(this.errors);
        merged.addAll(other.errors);
        ImageInfo.IIIFVersion version = this.detectedVersion != null ? this.detectedVersion : other.detectedVersion;
        return new ValidationResult(this.valid && other.valid, merged, version);
    }
}
