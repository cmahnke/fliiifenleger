// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IiifManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BASE_URI = "http://example.org/iiif/manifest";

    @Test
    void testV2ManifestStructure() throws Exception {
        IiifManifest manifest = new IiifManifest(ImageInfo.IIIFVersion.V2, BASE_URI, "Test Manifest");
        manifest.addCanvas("canvas1", "Canvas 1", "http://example.org/iiif/info1", 1024, 768);
        manifest.addCanvas("canvas2", "Canvas 2", "http://example.org/iiif/info2", 800, 600);

        JsonNode json = manifest.toJson();

        assertEquals("http://iiif.io/api/presentation/2/context.json", json.get("@context").asString());
        assertEquals("sc:Manifest", json.get("@type").asString());
        assertEquals(BASE_URI, json.get("@id").asString());
        assertEquals("Test Manifest", json.get("label").asString());
        assertEquals(1, json.get("sequences").size());
        assertEquals("sc:Sequence", json.get("sequences").get(0).get("@type").asString());

        JsonNode canvases = json.get("sequences").get(0).get("canvases");
        assertEquals(2, canvases.size());
        assertEquals("sc:Canvas", canvases.get(0).get("@type").asString());
        assertEquals("canvas1", canvases.get(0).get("@id").asString().substring(canvases.get(0).get("@id").asString().lastIndexOf('/') + 1));
    }

    @Test
    void testV3ManifestStructure() throws Exception {
        IiifManifest manifest = new IiifManifest(ImageInfo.IIIFVersion.V3, BASE_URI, "Test Manifest V3");
        manifest.addCanvas("canvas1", "Canvas 1", "http://example.org/iiif/info1", 1024, 768);

        JsonNode json = manifest.toJson();

        assertEquals("https://www.w3.org/ns/iiif/presentation/3/context.json", json.get("@context").asString());
        assertEquals("Manifest", json.get("type").asString());
        assertEquals(BASE_URI, json.get("id").asString());
        assertEquals("Test Manifest V3", json.get("label").asString());
        assertEquals(1, json.get("items").size());
        assertEquals("Canvas", json.get("items").get(0).get("type").asString());
    }

    @Test
    void testV2CanvasImageReference() throws Exception {
        IiifManifest manifest = new IiifManifest(ImageInfo.IIIFVersion.V2, BASE_URI, "Test");
        manifest.addCanvas("canvas1", "Canvas 1", BASE_URI + "/info.json", 1024, 768);

        JsonNode json = manifest.toJson();
        JsonNode images = json.get("sequences").get(0).get("canvases").get(0).get("images");
        assertEquals("oa:Annotation", images.get(0).get("@type").asString());
        assertEquals("dctypes:Image", images.get(0).get("resource").get("@type").asString());
        assertEquals(BASE_URI + "/info.json", images.get(0).get("resource").get("@id").asString());
    }

    @Test
    void testV3CanvasItems() throws Exception {
        IiifManifest manifest = new IiifManifest(ImageInfo.IIIFVersion.V3, BASE_URI, "Test");
        manifest.addCanvas("canvas1", "Canvas 1", BASE_URI + "/info.json", 1024, 768);

        JsonNode json = manifest.toJson();
        JsonNode canvasNode = json.get("items").get(0);
        assertEquals("Canvas", canvasNode.get("type").asString());
        assertEquals(BASE_URI + "/info.json", canvasNode.get("items").get(0).get("id").asString());
    }

    @Test
    void testFromJsonV2() throws Exception {
        String json = "{\n" +
                "  \"@context\": \"http://iiif.io/api/presentation/2/context.json\",\n" +
                "  \"@id\": \"http://example.org/iiif/manifest1\",\n" +
                "  \"@type\": \"sc:Manifest\",\n" +
                "  \"label\": \"Original Manifest\",\n" +
                "  \"sequences\": [{\n" +
                "    \"@type\": \"sc:Sequence\",\n" +
                "    \"canvases\": [{\n" +
                "      \"@id\": \"http://example.org/iiif/manifest1/canvas/c1\",\n" +
                "      \"@type\": \"sc:Canvas\",\n" +
                "      \"width\": 1024,\n" +
                "      \"height\": 768,\n" +
                "      \"images\": [{\n" +
                "        \"@type\": \"oa:Annotation\",\n" +
                "        \"resource\": { \"@id\": \"http://example.org/iiif/manifest1/info.json\" }\n" +
                "      }]\n" +
                "    }]\n" +
                "  }]\n" +
                "}";

        IiifManifest manifest = IiifManifest.fromJson(json);
        assertEquals(ImageInfo.IIIFVersion.V2, manifest.getVersion());
        assertEquals("http://example.org/iiif/manifest1", manifest.getBaseUri());
        assertEquals("Original Manifest", manifest.getLabel());
        assertEquals(1, manifest.getCanvases().size());
        assertEquals("c1", manifest.getCanvases().get(0).id);
    }

    @Test
    void testFromJsonV3() throws Exception {
        String json = "{\n" +
                "  \"@context\": \"https://www.w3.org/ns/iiif/presentation/3/context.json\",\n" +
                "  \"id\": \"http://example.org/iiif/manifest2\",\n" +
                "  \"type\": \"Manifest\",\n" +
                "  \"label\": \"V3 Manifest\",\n" +
                "  \"items\": [{\n" +
                "    \"id\": \"http://example.org/iiif/manifest2/canvas/c1\",\n" +
                "    \"type\": \"Canvas\",\n" +
                "    \"width\": 1024,\n" +
                "    \"height\": 768,\n" +
                "    \"items\": [{\"id\": \"http://example.org/iiif/manifest2/info.json\", \"type\": \"Image\"}]\n" +
                "  }]\n" +
                "}";

        IiifManifest manifest = IiifManifest.fromJson(json);
        assertEquals(ImageInfo.IIIFVersion.V3, manifest.getVersion());
        assertEquals("http://example.org/iiif/manifest2", manifest.getBaseUri());
        assertEquals("V3 Manifest", manifest.getLabel());
        assertEquals(1, manifest.getCanvases().size());
        assertEquals("c1", manifest.getCanvases().get(0).id);
    }

    @Test
    void testReplaceBaseUriV2() throws Exception {
        String oldBase = "http://old.example.org";
        String newBase = "http://new.example.org";
        String json = "{\n" +
                "  \"@context\": \"http://iiif.io/api/presentation/2/context.json\",\n" +
                "  \"@id\": \"" + oldBase + "/manifest\",\n" +
                "  \"@type\": \"sc:Manifest\",\n" +
                "  \"label\": \"Test\",\n" +
                "  \"sequences\": [{\n" +
                "    \"@type\": \"sc:Sequence\",\n" +
                "    \"canvases\": [{\n" +
                "      \"@id\": \"" + oldBase + "/manifest/canvas/c1\",\n" +
                "      \"@type\": \"sc:Canvas\",\n" +
                "      \"width\": 1024,\n" +
                "      \"height\": 768\n" +
                "    }]\n" +
                "  }]\n" +
                "}";

        String result = IiifManifest.replaceBaseUriInJson(json, oldBase, newBase);
        JsonNode node = MAPPER.readTree(result);

        assertEquals(newBase + "/manifest", node.get("@id").asString());
        assertEquals(newBase + "/manifest/canvas/c1", node.get("sequences").get(0).get("canvases").get(0).get("@id").asString());
    }

    @Test
    void testReplaceBaseUriV3() throws Exception {
        String oldBase = "http://old.example.org";
        String newBase = "http://new.example.org";
        String json = "{\n" +
                "  \"@context\": \"https://www.w3.org/ns/iiif/presentation/3/context.json\",\n" +
                "  \"id\": \"" + oldBase + "/manifest\",\n" +
                "  \"type\": \"Manifest\",\n" +
                "  \"items\": [{\n" +
                "    \"id\": \"" + oldBase + "/manifest/canvas/c1\",\n" +
                "    \"type\": \"Canvas\"\n" +
                "  }]\n" +
                "}";

        String result = IiifManifest.replaceBaseUriInJson(json, oldBase, newBase);
        JsonNode node = MAPPER.readTree(result);

        assertEquals(newBase + "/manifest", node.get("id").asString());
        assertEquals(newBase + "/manifest/canvas/c1", node.get("items").get(0).get("id").asString());
    }

    @Test
    void testNoReplaceWhenOldBaseNotPresent() throws Exception {
        String json = "{\n" +
                "  \"@context\": \"http://iiif.io/api/presentation/2/context.json\",\n" +
                "  \"@id\": \"http://unchanged.example.org/manifest\",\n" +
                "  \"@type\": \"sc:Manifest\"\n" +
                "}";

        String result = IiifManifest.replaceBaseUriInJson(json, "http://other.example.org", "http://new.example.org");
        JsonNode node = MAPPER.readTree(result);
        assertEquals("http://unchanged.example.org/manifest", node.get("@id").asString());
    }

    @Test
    void testToPrettyJson() throws Exception {
        IiifManifest manifest = new IiifManifest(ImageInfo.IIIFVersion.V2, BASE_URI);
        manifest.addCanvas("c1", "Test", BASE_URI + "/info", 1024, 768);
        String json = IiifManifest.toPrettyJson(manifest.toJson());
        assertNotNull(json);
        assertTrue(json.contains("@context"));
    }
}
