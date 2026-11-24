// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/**
 * Schemas for IIIF Presentation API manifests.
 *
 * <p>Both schema sets are downloaded at build time from the {@code main}
 * branch of {@code IIIF/presentation-validator}, which tracks the moving V4
 * target. The v3 schema is the self-contained draft-07 schema; the v4 schemas
 * are the draft 2020-12 set. Their {@code $id} values use the prefix
 * {@code https://iiif.io/api/presentation/4.0/schema}, which is mapped to the
 * generated resources so relative {@code $ref}s resolve without runtime
 * network access.
 */
public final class PresentationSchemaRegistry {

    public static final String V4_SCHEMA_ID_PREFIX = "https://iiif.io/api/presentation/4.0/schema";

    private static final String V3_SCHEMA_RESOURCE = "classpath:schema/presentation/iiif_3_0.json";
    private static final String V4_ROOT_SCHEMA = V4_SCHEMA_ID_PREFIX + "/main.json";

    private static final Schema V3_SCHEMA = loadV3();
    private static final Schema V4_SCHEMA = loadV4();

    private PresentationSchemaRegistry() {
    }

    public static Schema schemaFor(ImageInfo.IIIFVersion version) {
        return switch (version) {
            case V3 -> V3_SCHEMA;
            case V4 -> V4_SCHEMA;
            default -> throw new IllegalArgumentException(
                    "No Presentation API schema for version " + version + "; use V3 or V4.");
        };
    }

    /** Forces resolution of all external references so broken bundles fail at startup. */
    public static void initialize() {
        V3_SCHEMA.initializeValidators();
        V4_SCHEMA.initializeValidators();
    }

    private static Schema loadV3() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        return registry.getSchema(SchemaLocation.of(V3_SCHEMA_RESOURCE));
    }

    private static Schema loadV4() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaIdResolvers(resolvers ->
                        resolvers.mapPrefix(V4_SCHEMA_ID_PREFIX, "classpath:schema/presentation/v4")));
        return registry.getSchema(SchemaLocation.of(V4_ROOT_SCHEMA));
    }
}
