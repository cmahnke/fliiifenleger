// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.validation.ValidationResult;
import de.christianmahnke.iiif.fliiifenleger.validation.ValidationSupport;
import de.christianmahnke.iiif.fliiifenleger.validation.Validator;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Module-owned {@code info.json} semantics for UltraHDR gain-map tiles
 * ({@link UltraHdrTileSink#HDR_PROFILE_URI}).
 *
 * <p>Checks service entries claiming the HDR profile: the advertised gain-map
 * service must be complete (non-blank {@code id}, {@code type} and
 * {@code profile}) and the HDR JSON-LD context must be present in a V3
 * {@code @context} array. Entries with other profiles are ignored.
 */
@AutoService(Validator.class)
public class HdrValidator implements Validator {

    @Override
    public String getName() {
        return "hdr";
    }

    @Override
    public String getDescription() {
        return "Validates UltraHDR gain-map service entries (completeness, context presence).";
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
            if (!UltraHdrTileSink.HDR_PROFILE_URI.equals(ValidationSupport.textOrNull(entry.get("profile")))) {
                continue;
            }
            if (!contexts.contains(UltraHdrTileSink.HDR_CONTEXT_URI)) {
                errors.add("service: service '" + UltraHdrTileSink.HDR_PROFILE_URI + "' requires @context to contain "
                        + UltraHdrTileSink.HDR_CONTEXT_URI);
            }
            for (String field : List.of("id", "type", "profile")) {
                String value = ValidationSupport.textOrNull(entry.get(field));
                if (value == null || value.isBlank()) {
                    errors.add("service: HDR gain-map service entry must carry a non-blank '" + field + "'");
                }
            }
        }
        return errors.isEmpty() ? ValidationResult.ok(null) : ValidationResult.failures(errors);
    }
}
