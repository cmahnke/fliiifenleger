// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PresentationSchemaRegistry")
class PresentationSchemaRegistryTest {

    private static final Pattern REF = Pattern.compile("\"\\$ref\"\\s*:\\s*\"([^\"#]+\\.json)");

    @Test
    @DisplayName("every relative v4 $ref resolves case-sensitively")
    void v4ReferencesResolve() throws Exception {
        Path dir = Path.of(Objects.requireNonNull(
                PresentationSchemaRegistry.class.getResource("/schema/presentation/v4")).toURI());
        Set<String> files = new HashSet<>();
        try (var paths = Files.list(dir)) {
            paths.filter(p -> p.toString().endsWith(".json")).forEach(p -> files.add(p.getFileName().toString()));
        }
        Set<String> missing = new HashSet<>();
        for (String file : files) {
            String text = Files.readString(dir.resolve(file));
            Matcher matcher = REF.matcher(text);
            while (matcher.find()) {
                if (!files.contains(matcher.group(1))) {
                    missing.add(file + " -> " + matcher.group(1));
                }
            }
        }
        assertTrue(missing.isEmpty(), () -> "Unresolved refs: " + missing);
    }
}
