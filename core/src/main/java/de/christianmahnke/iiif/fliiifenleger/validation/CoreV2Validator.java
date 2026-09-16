// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.validation;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Core IIIF rules for Image API 2: the fixed {@code @context} offers no place
 * for namespaced extension properties, and the embedded profile must start
 * with the Image API 2 compliance level URI.
 */
@AutoService(Validator.class)
public class CoreV2Validator implements Validator {

    @Override
    public String getName() {
        return "core-v2";
    }

    @Override
    public String getDescription() {
        return "Enforces IIIF Image API 2 structural rules (fixed @context, profile level).";
    }

    @Override
    public boolean supports(ImageInfo.IIIFVersion version) {
        return version == ImageInfo.IIIFVersion.V2;
    }

    @Override
    public ValidationResult validate(JsonNode node, ImageInfo.IIIFVersion effectiveVersion) {
        List<String> errors = new ArrayList<>();
        if (node.has("trustAnchor") || ValidationSupport.findKey(node, "trustAnchor")) {
            errors.add("Image API 2 must not contain the namespaced 'trustAnchor' property "
                    + "(fixed @context); use Image API 3");
        }
        JsonNode profile = node.get("profile");
        if (profile != null && profile.isArray() && !profile.isEmpty()) {
            JsonNode first = profile.get(0);
            if (!first.isString() || !first.asString().matches("^http://iiif\\.io/api/image/2/level[0-2]\\.json$")) {
                errors.add("profile: first entry must be the Image API 2 compliance level URI");
            }
        }
        return errors.isEmpty() ? ValidationResult.ok(null) : ValidationResult.failures(errors);
    }
}
