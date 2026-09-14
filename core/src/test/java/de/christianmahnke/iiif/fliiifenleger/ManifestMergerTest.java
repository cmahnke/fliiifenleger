// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestMergerTest {

    private static final String BASE_URI = "http://example.org/iiif/manifest";
    private static final String OLD_BASE1 = "http://manifest1.example.org";
    private static final String OLD_BASE2 = "http://manifest2.example.org";

    private static final String V2_MANIFEST1 = "{\n" +
            "  \"@context\": \"http://iiif.io/api/presentation/2/context.json\",\n" +
            "  \"@id\": \"" + OLD_BASE1 + "/manifest1\",\n" +
            "  \"@type\": \"sc:Manifest\",\n" +
            "  \"label\": \"Manifest 1\",\n" +
            "  \"sequences\": [{\n" +
            "    \"@type\": \"sc:Sequence\",\n" +
            "    \"canvases\": [{\n" +
            "      \"@id\": \"" + OLD_BASE1 + "/manifest1/canvas/c1\",\n" +
            "      \"@type\": \"sc:Canvas\",\n" +
            "      \"width\": 1024,\n" +
            "      \"height\": 768,\n" +
            "      \"images\": [{\"@type\": \"oa:Annotation\", \"resource\": {\"@id\": \"" + OLD_BASE1 + "/manifest1/info.json\"}}]\n" +
            "    }]\n" +
            "  }]\n" +
            "}";

    private static final String V2_MANIFEST2 = "{\n" +
            "  \"@context\": \"http://iiif.io/api/presentation/2/context.json\",\n" +
            "  \"@id\": \"" + OLD_BASE2 + "/manifest2\",\n" +
            "  \"@type\": \"sc:Manifest\",\n" +
            "  \"label\": \"Manifest 2\",\n" +
            "  \"sequences\": [{\n" +
            "    \"@type\": \"sc:Sequence\",\n" +
            "    \"canvases\": [{\n" +
            "      \"@id\": \"" + OLD_BASE2 + "/manifest2/canvas/c2\",\n" +
            "      \"@type\": \"sc:Canvas\",\n" +
            "      \"width\": 2048,\n" +
            "      \"height\": 1536,\n" +
            "      \"images\": [{\"@type\": \"oa:Annotation\", \"resource\": {\"@id\": \"" + OLD_BASE2 + "/manifest2/info.json\"}}]\n" +
            "    }]\n" +
            "  }]\n" +
            "}";

    private static final String V3_MANIFEST = "{\n" +
            "  \"@context\": \"https://www.w3.org/ns/iiif/presentation/3/context.json\",\n" +
            "  \"id\": \"" + OLD_BASE1 + "/manifest3\",\n" +
            "  \"type\": \"Manifest\",\n" +
            "  \"label\": \"V3 Manifest\",\n" +
            "  \"items\": [{\n" +
            "    \"id\": \"" + OLD_BASE1 + "/manifest3/canvas/c1\",\n" +
            "    \"type\": \"Canvas\",\n" +
            "    \"width\": 1024,\n" +
            "    \"height\": 768,\n" +
            "    \"items\": [{\"id\": \"" + OLD_BASE1 + "/manifest3/info.json\", \"type\": \"Image\"}]\n" +
            "  }]\n" +
            "}";

    @Test
    void testMergeTwoV2Manifests(@TempDir Path tempDir) throws Exception {
        Path manifest1Path = tempDir.resolve("manifest1.json");
        Path manifest2Path = tempDir.resolve("manifest2.json");
        java.nio.file.Files.writeString(manifest1Path, V2_MANIFEST1);
        java.nio.file.Files.writeString(manifest2Path, V2_MANIFEST2);

        ManifestMerger merger = new ManifestMerger(ImageInfo.IIIFVersion.V2, BASE_URI);
        merger.addManifest(manifest1Path);
        merger.addManifest(manifest2Path);

        String result = merger.mergeToJson();
        JsonNode node = new ObjectMapper().readTree(result);

        assertEquals("sc:Manifest", node.get("@type").asText());
        assertEquals(BASE_URI, node.get("@id").asText());
        assertEquals(2, node.get("sequences").get(0).get("canvases").size());
        assertEquals(2, merger.getManifestCount());
    }

    @Test
    void testMergeReplacesBaseUris(@TempDir Path tempDir) throws Exception {
        Path manifest1Path = tempDir.resolve("manifest1.json");
        java.nio.file.Files.writeString(manifest1Path, V2_MANIFEST1);

        ManifestMerger merger = new ManifestMerger(ImageInfo.IIIFVersion.V2, BASE_URI);
        merger.addManifest(manifest1Path);

        String result = merger.mergeToJson();
        JsonNode node = new ObjectMapper().readTree(result);

        assertEquals(BASE_URI, node.get("@id").asText());
        assertTrue(node.get("sequences").get(0).get("canvases").get(0).get("@id").asText().startsWith(BASE_URI));
        assertFalse(node.toString().contains(OLD_BASE1), "Old base URI should not appear in output");
    }

    @Test
    void testMergeTwoV2ManifestsStatic(@TempDir Path tempDir) throws Exception {
        Path manifest1Path = tempDir.resolve("manifest1.json");
        Path manifest2Path = tempDir.resolve("manifest2.json");
        java.nio.file.Files.writeString(manifest1Path, V2_MANIFEST1);
        java.nio.file.Files.writeString(manifest2Path, V2_MANIFEST2);

        String result = ManifestMerger.mergeManifestsFromPaths(
                List.of(manifest1Path, manifest2Path),
                ImageInfo.IIIFVersion.V2,
                BASE_URI
        );

        JsonNode node = new ObjectMapper().readTree(result);
        assertEquals(2, node.get("sequences").get(0).get("canvases").size());
        assertEquals(BASE_URI, node.get("@id").asText());
    }

    @Test
    void testMergeV3Manifests(@TempDir Path tempDir) throws Exception {
        Path manifest1Path = tempDir.resolve("manifest.json");
        java.nio.file.Files.writeString(manifest1Path, V3_MANIFEST);

        ManifestMerger merger = new ManifestMerger(ImageInfo.IIIFVersion.V3, BASE_URI);
        merger.addManifest(manifest1Path);

        String result = merger.mergeToJson();
        JsonNode node = new ObjectMapper().readTree(result);

        assertEquals("Manifest", node.get("type").asText());
        assertEquals("https://www.w3.org/ns/iiif/presentation/3/context.json", node.get("@context").asText());
        assertEquals(1, node.get("items").size());
    }

    @Test
    void testMergeWithStaticMethod(@TempDir Path tempDir) throws Exception {
        Path manifest1Path = tempDir.resolve("manifest1.json");
        Path manifest2Path = tempDir.resolve("manifest2.json");
        java.nio.file.Files.writeString(manifest1Path, V2_MANIFEST1);
        java.nio.file.Files.writeString(manifest2Path, V2_MANIFEST2);

        String result = ManifestMerger.mergeManifestsFromPaths(
                List.of(manifest1Path, manifest2Path),
                ImageInfo.IIIFVersion.V2,
                BASE_URI
        );

        JsonNode node = new ObjectMapper().readTree(result);
        assertEquals(2, node.get("sequences").get(0).get("canvases").size());
        assertEquals(BASE_URI, node.get("@id").asText());
    }

    @Test
    void testMergePreservesLabels(@TempDir Path tempDir) throws Exception {
        Path manifest1Path = tempDir.resolve("manifest1.json");
        Path manifest2Path = tempDir.resolve("manifest2.json");
        java.nio.file.Files.writeString(manifest1Path, V2_MANIFEST1);
        java.nio.file.Files.writeString(manifest2Path, V2_MANIFEST2);

        ManifestMerger merger = new ManifestMerger(ImageInfo.IIIFVersion.V2, BASE_URI);
        merger.addManifest(manifest1Path);
        merger.addManifest(manifest2Path);

        String result = merger.mergeToJson();
        JsonNode node = new ObjectMapper().readTree(result);
        assertTrue(node.get("label").asText().contains("Manifest 1"));
        assertTrue(node.get("label").asText().contains("Manifest 2"));
    }

    @Test
    void testManifestCount(@TempDir Path tempDir) throws Exception {
        Path manifest1Path = tempDir.resolve("manifest1.json");
        Path manifest2Path = tempDir.resolve("manifest2.json");
        java.nio.file.Files.writeString(manifest1Path, V2_MANIFEST1);
        java.nio.file.Files.writeString(manifest2Path, V2_MANIFEST2);

        ManifestMerger merger = new ManifestMerger(ImageInfo.IIIFVersion.V2, BASE_URI);
        merger.addManifest(manifest1Path);
        merger.addManifest(manifest2Path);

        assertEquals(2, merger.getManifestCount());
    }
}
