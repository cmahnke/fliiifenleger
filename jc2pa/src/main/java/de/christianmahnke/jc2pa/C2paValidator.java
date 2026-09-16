// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.jc2pa;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.validation.ValidationResult;
import de.christianmahnke.iiif.fliiifenleger.validation.ValidationSupport;
import de.christianmahnke.iiif.fliiifenleger.validation.Validator;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Module-owned {@code info.json} semantics for C2PA Content Credentials
 * ({@link C2paTileSink#C2PA_PROFILE_URI}).
 *
 * <p>Checks service entries claiming the C2PA profile: the C2PA JSON-LD
 * context must be present in a V3 {@code @context} array, and an advertised
 * {@code trustAnchor} must be an absolute URI. Entries with other profiles
 * are ignored.
 */
@AutoService(Validator.class)
public class C2paValidator implements Validator {

    @Override
    public String getName() {
        return "c2pa";
    }

    @Override
    public String getDescription() {
        return "Validates C2PA service entries (context presence, trustAnchor URI).";
    }

    @Override
    public boolean supports(ImageInfo.IIIFVersion version) {
        return version == ImageInfo.IIIFVersion.V3;
    }

    @Override
    public ValidationResult validate(JsonNode node, ImageInfo.IIIFVersion effectiveVersion) {
        List<String> errors = new ArrayList<>();
        List<String> contexts = ValidationSupport.collectContexts(node);
        for (JsonNode entry : ValidationSupport.serviceEntries(node)) {
            if (!C2paTileSink.C2PA_PROFILE_URI.equals(ValidationSupport.textOrNull(entry.get("profile")))) {
                continue;
            }
            if (!contexts.contains(C2paTileSink.C2PA_CONTEXT_URI)) {
                errors.add("service: service '" + C2paTileSink.C2PA_PROFILE_URI + "' requires @context to contain "
                        + C2paTileSink.C2PA_CONTEXT_URI);
            }
            JsonNode anchor = entry.get("trustAnchor");
            if (anchor != null && (!anchor.isString() || !ValidationSupport.isAbsoluteUri(anchor.asString()))) {
                errors.add("service: trustAnchor must be an absolute URI");
            }
        }
        return errors.isEmpty() ? ValidationResult.ok(null) : ValidationResult.failures(errors);
    }
}
