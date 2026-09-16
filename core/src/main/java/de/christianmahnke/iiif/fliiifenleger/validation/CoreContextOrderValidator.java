// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.validation;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.InfoJsonValidator;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Core IIIF rule: a V3 {@code @context} array must end with the IIIF Image
 * API 3 context (a constraint JSON Schema expresses poorly).
 */
@AutoService(Validator.class)
public class CoreContextOrderValidator implements Validator {

    @Override
    public String getName() {
        return "core-context";
    }

    @Override
    public String getDescription() {
        return "Enforces IIIF Image API @context ordering rules.";
    }

    @Override
    public boolean supports(ImageInfo.IIIFVersion version) {
        return version == ImageInfo.IIIFVersion.V3;
    }

    @Override
    public ValidationResult validate(JsonNode node, ImageInfo.IIIFVersion effectiveVersion) {
        List<String> errors = new ArrayList<>();
        JsonNode context = node.get("@context");
        if (context != null && context.isArray() && !context.isEmpty()) {
            JsonNode last = context.get(context.size() - 1);
            if (!last.isString() || !InfoJsonValidator.V3_CONTEXT.equals(last.asString())) {
                errors.add("@context: the IIIF Image API 3 context must be the last entry of the array");
            }
        }
        return errors.isEmpty() ? ValidationResult.ok(null) : ValidationResult.failures(errors);
    }
}
