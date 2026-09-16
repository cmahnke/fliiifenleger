// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ManifestUpdaterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static final String V2_MANIFEST = """
            {
              "@context": "http://iiif.io/api/presentation/2/context.json",
              "@id": "http://example.org/iiif/manifest",
              "@type": "sc:Manifest",
              "label": "Test Manifest",
              "sequences": [{
                "@type": "sc:Sequence",
                "canvases": [
                  {
                    "@id": "http://example.org/iiif/manifest/canvas/c1",
                    "@type": "sc:Canvas",
                    "label": "page1",
                    "width": 1024,
                    "height": 768
                  },
                  {
                    "@id": "http://example.org/iiif/manifest/canvas/c2",
                    "@type": "sc:Canvas",
                    "label": "page2",
                    "width": 1024,
                    "height": 768
                  }
                ]
              }]
            }
            """;

    private static final String V3_MANIFEST = """
            {
              "@context": "https://www.w3.org/ns/iiif/presentation/3/context.json",
              "id": "http://example.org/iiif/manifest",
              "type": "Manifest",
              "label": "Test Manifest",
              "items": [
                {
                  "id": "http://example.org/iiif/manifest/canvas/c1",
                  "type": "Canvas",
                  "label": "page1",
                  "width": 1024,
                  "height": 768,
                  "items": [{"id": "http://example.org/iiif/manifest/info.json", "type": "Image"}]
                },
                {
                  "id": "http://example.org/iiif/manifest/canvas/c2",
                  "type": "Canvas",
                  "label": "page2",
                  "width": 1024,
                  "height": 768,
                  "items": [{"id": "http://example.org/iiif/manifest/info2.json", "type": "Image"}]
                }
              ]
            }
            """;

    @Test
    void testUpdateV2ManifestWithTeiFiles() throws Exception {
        Files.writeString(tempDir.resolve("page1.xml"), "<TEI/>");
        Files.writeString(tempDir.resolve("page2.xml"), "<TEI/>");

        String result = ManifestUpdater.updateWithTeiFiles(V2_MANIFEST, tempDir, null);
        JsonNode json = MAPPER.readTree(result);

        JsonNode canvases = json.get("sequences").get(0).get("canvases");
        assertEquals(2, canvases.size());

        JsonNode seeAlso1 = canvases.get(0).get("seeAlso");
        assertNotNull(seeAlso1);
        assertEquals(1, seeAlso1.size());
        assertEquals("http://example.org/iiif/manifest/page1.xml", seeAlso1.get(0).get("id").asString());
        assertEquals("Dataset", seeAlso1.get(0).get("type").asString());
        assertEquals("application/tei+xml", seeAlso1.get(0).get("format").asString());
        assertEquals("http://tei-c.org", seeAlso1.get(0).get("profile").asString());

        JsonNode seeAlso2 = canvases.get(1).get("seeAlso");
        assertNotNull(seeAlso2);
        assertEquals(1, seeAlso2.size());
        assertEquals("http://example.org/iiif/manifest/page2.xml", seeAlso2.get(0).get("id").asString());
    }

    @Test
    void testUpdateV3ManifestWithTeiFiles() throws Exception {
        Files.writeString(tempDir.resolve("page1.xml"), "<TEI/>");
        Files.writeString(tempDir.resolve("page2.xml"), "<TEI/>");

        String result = ManifestUpdater.updateWithTeiFiles(V3_MANIFEST, tempDir, null);
        JsonNode json = MAPPER.readTree(result);

        JsonNode items = json.get("items");
        assertEquals(2, items.size());

        JsonNode seeAlso1 = items.get(0).get("seeAlso");
        assertNotNull(seeAlso1);
        assertEquals(1, seeAlso1.size());
        assertEquals("http://example.org/iiif/manifest/page1.xml", seeAlso1.get(0).get("id").asString());
        assertEquals("Dataset", seeAlso1.get(0).get("type").asString());
        assertEquals("application/tei+xml", seeAlso1.get(0).get("format").asString());
        assertEquals("http://tei-c.org", seeAlso1.get(0).get("profile").asString());
    }

    @Test
    void testCustomTeiBaseUrl() throws Exception {
        Files.writeString(tempDir.resolve("page1.xml"), "<TEI/>");

        String result = ManifestUpdater.updateWithTeiFiles(V2_MANIFEST, tempDir, "http://custom.org/tei");
        JsonNode json = MAPPER.readTree(result);

        JsonNode seeAlso = json.get("sequences").get(0).get("canvases").get(0).get("seeAlso");
        assertNotNull(seeAlso);
        assertEquals("http://custom.org/tei/page1.xml", seeAlso.get(0).get("id").asString());
    }

    @Test
    void testUnmatchedTeiFileSkipped() throws Exception {
        Files.writeString(tempDir.resolve("page1.xml"), "<TEI/>");
        Files.writeString(tempDir.resolve("unmatched.xml"), "<TEI/>");

        String result = ManifestUpdater.updateWithTeiFiles(V2_MANIFEST, tempDir, null);
        JsonNode json = MAPPER.readTree(result);

        JsonNode canvases = json.get("sequences").get(0).get("canvases");
        JsonNode seeAlso1 = canvases.get(0).get("seeAlso");
        assertNotNull(seeAlso1);
        assertEquals(1, seeAlso1.size());

        JsonNode seeAlso2 = canvases.get(1).get("seeAlso");
        assertTrue(seeAlso2 == null || seeAlso2.isEmpty());
    }

    @Test
    void testCaseInsensitiveMatching() throws Exception {
        Files.writeString(tempDir.resolve("Page1.XML"), "<TEI/>");

        String result = ManifestUpdater.updateWithTeiFiles(V2_MANIFEST, tempDir, null);
        JsonNode json = MAPPER.readTree(result);

        JsonNode seeAlso = json.get("sequences").get(0).get("canvases").get(0).get("seeAlso");
        assertNotNull(seeAlso);
        assertEquals(1, seeAlso.size());
        assertEquals("http://example.org/iiif/manifest/Page1.XML", seeAlso.get(0).get("id").asString());
    }

    @Test
    void testTeiExtension() throws Exception {
        Files.writeString(tempDir.resolve("page1.tei"), "<TEI/>");

        String result = ManifestUpdater.updateWithTeiFiles(V2_MANIFEST, tempDir, null);
        JsonNode json = MAPPER.readTree(result);

        JsonNode seeAlso = json.get("sequences").get(0).get("canvases").get(0).get("seeAlso");
        assertNotNull(seeAlso);
        assertEquals(1, seeAlso.size());
        assertEquals("http://example.org/iiif/manifest/page1.tei", seeAlso.get(0).get("id").asString());
    }

    @Test
    void testScanTeiFolder() throws Exception {
        Files.writeString(tempDir.resolve("file1.xml"), "<TEI/>");
        Files.writeString(tempDir.resolve("file2.tei"), "<TEI/>");
        Files.writeString(tempDir.resolve("file3.txt"), "not a tei file");
        Files.writeString(tempDir.resolve("file4.XML"), "<TEI/>");

        Map<String, String> teiFiles = ManifestUpdater.scanTeiFolder(tempDir);

        assertEquals(3, teiFiles.size());
        assertTrue(teiFiles.containsKey("file1"));
        assertTrue(teiFiles.containsKey("file2"));
        assertTrue(teiFiles.containsKey("file4"));
        assertFalse(teiFiles.containsKey("file3"));
    }

    @Test
    void testGetBaseName() {
        assertEquals("page1", ManifestUpdater.getBaseName("page1.xml"));
        assertEquals("page1", ManifestUpdater.getBaseName("page1.tei"));
        assertEquals("file", ManifestUpdater.getBaseName("file"));
        assertEquals("my.file", ManifestUpdater.getBaseName("my.file.xml"));
    }

    @Test
    void testExtractCanvasKey() throws Exception {
        IiifManifest.CanvasRef canvasWithLabel = new IiifManifest.CanvasRef("c1", "Page 1", null, 100, 100);
        assertEquals("page 1", ManifestUpdater.extractCanvasKey(canvasWithLabel));

        IiifManifest.CanvasRef canvasWithId = new IiifManifest.CanvasRef("c1", null, null, 100, 100);
        assertEquals("c1", ManifestUpdater.extractCanvasKey(canvasWithId));
    }

    @Test
    void testExistingSeeAlsoPreserved() throws Exception {
        String manifestWithSeeAlso = V2_MANIFEST.replace(
                "\"label\": \"page1\"",
                "\"label\": \"page1\", \"seeAlso\": [{\"id\": \"http://existing.org/doc.xml\", \"type\": \"Dataset\", \"format\": \"application/xml\", \"profile\": \"http://example.org\"}]"
        );

        Files.writeString(tempDir.resolve("page1.xml"), "<TEI/>");

        String result = ManifestUpdater.updateWithTeiFiles(manifestWithSeeAlso, tempDir, null);
        JsonNode json = MAPPER.readTree(result);

        JsonNode seeAlso = json.get("sequences").get(0).get("canvases").get(0).get("seeAlso");
        assertNotNull(seeAlso);
        assertEquals(2, seeAlso.size());
        assertEquals("http://existing.org/doc.xml", seeAlso.get(0).get("id").asString());
        assertEquals("http://example.org/iiif/manifest/page1.xml", seeAlso.get(1).get("id").asString());
    }

    @Test
    void testMusicXmlGlobAndMediaType() throws Exception {
        Files.writeString(tempDir.resolve("page1.musicxml"), "<score/>");
        Files.writeString(tempDir.resolve("page2.musicxml"), "<score/>");

        String result = ManifestUpdater.updateWithSeeAlsoFiles(V2_MANIFEST, tempDir, "*.musicxml", null, null, null, null);
        JsonNode json = MAPPER.readTree(result);

        JsonNode canvases = json.get("sequences").get(0).get("canvases");
        JsonNode seeAlso1 = canvases.get(0).get("seeAlso");
        assertNotNull(seeAlso1);
        assertEquals(1, seeAlso1.size());
        assertEquals("http://example.org/iiif/manifest/page1.musicxml", seeAlso1.get(0).get("id").asString());
        assertEquals("application/vnd.recordare.musicxml+xml", seeAlso1.get(0).get("format").asString());
        assertEquals("Dataset", seeAlso1.get(0).get("type").asString());
        assertNull(seeAlso1.get(0).get("profile"));

        JsonNode seeAlso2 = canvases.get(1).get("seeAlso");
        assertNotNull(seeAlso2);
        assertEquals("application/vnd.recordare.musicxml+xml", seeAlso2.get(0).get("format").asString());
    }

    @Test
    void testCompressedMusicXmlMediaType() throws Exception {
        Files.writeString(tempDir.resolve("page1.mxl"), "fake-zip");

        String result = ManifestUpdater.updateWithSeeAlsoFiles(V2_MANIFEST, tempDir, "*.mxl", null, null, null, null);
        JsonNode json = MAPPER.readTree(result);

        JsonNode seeAlso = json.get("sequences").get(0).get("canvases").get(0).get("seeAlso");
        assertNotNull(seeAlso);
        assertEquals("application/vnd.recordare.musicxml", seeAlso.get(0).get("format").asString());
        assertNull(seeAlso.get(0).get("profile"));
    }

    @Test
    void testCompoundTeiXmlPattern() throws Exception {
        Files.writeString(tempDir.resolve("page1.tei.xml"), "<TEI/>");

        String result = ManifestUpdater.updateWithSeeAlsoFiles(V2_MANIFEST, tempDir, "*.tei.xml", null, null, null, null);
        JsonNode json = MAPPER.readTree(result);

        JsonNode canvases = json.get("sequences").get(0).get("canvases");
        JsonNode seeAlso1 = canvases.get(0).get("seeAlso");
        assertNotNull(seeAlso1);
        assertEquals(1, seeAlso1.size());
        assertEquals("http://example.org/iiif/manifest/page1.tei.xml", seeAlso1.get(0).get("id").asString());
        assertEquals("application/tei+xml", seeAlso1.get(0).get("format").asString());
        assertEquals("http://tei-c.org", seeAlso1.get(0).get("profile").asString());

        // page2 has no file, so no seeAlso
        JsonNode seeAlso2 = canvases.get(1).get("seeAlso");
        assertTrue(seeAlso2 == null || seeAlso2.isEmpty());
    }

    @Test
    void testGlobFiltersNonMatchingFiles() throws Exception {
        Files.writeString(tempDir.resolve("page1.xml"), "<TEI/>");
        Files.writeString(tempDir.resolve("page1.musicxml"), "<score/>");

        String result = ManifestUpdater.updateWithSeeAlsoFiles(V2_MANIFEST, tempDir, "*.musicxml", null, null, null, null);
        JsonNode json = MAPPER.readTree(result);

        JsonNode seeAlso = json.get("sequences").get(0).get("canvases").get(0).get("seeAlso");
        assertNotNull(seeAlso);
        assertEquals(1, seeAlso.size());
        assertEquals("http://example.org/iiif/manifest/page1.musicxml", seeAlso.get(0).get("id").asString());
    }

    @Test
    void testCaseInsensitiveGlob() throws Exception {
        Files.writeString(tempDir.resolve("Page1.MUSICXML"), "<score/>");

        String result = ManifestUpdater.updateWithSeeAlsoFiles(V2_MANIFEST, tempDir, "*.musicxml", null, null, null, null);
        JsonNode json = MAPPER.readTree(result);

        JsonNode seeAlso = json.get("sequences").get(0).get("canvases").get(0).get("seeAlso");
        assertNotNull(seeAlso);
        assertEquals(1, seeAlso.size());
        assertEquals("http://example.org/iiif/manifest/Page1.MUSICXML", seeAlso.get(0).get("id").asString());
        assertEquals("application/vnd.recordare.musicxml+xml", seeAlso.get(0).get("format").asString());
    }

    @Test
    void testBraceExpansionMixedFolder() throws Exception {
        Files.writeString(tempDir.resolve("page1.musicxml"), "<score/>");
        Files.writeString(tempDir.resolve("page2.mxl"), "fake-zip");

        String result = ManifestUpdater.updateWithSeeAlsoFiles(V2_MANIFEST, tempDir, "*.{musicxml,mxl}", null, null, null, null);
        JsonNode json = MAPPER.readTree(result);

        JsonNode canvases = json.get("sequences").get(0).get("canvases");
        assertEquals("application/vnd.recordare.musicxml+xml", canvases.get(0).get("seeAlso").get(0).get("format").asString());
        assertEquals("application/vnd.recordare.musicxml", canvases.get(1).get("seeAlso").get(0).get("format").asString());
    }

    @Test
    void testFormatOverride() throws Exception {
        Files.writeString(tempDir.resolve("page1.musicxml"), "<score/>");

        String result = ManifestUpdater.updateWithSeeAlsoFiles(V2_MANIFEST, tempDir, "*.musicxml", null,
                "application/custom+xml", "Text", "http://example.org/profile");
        JsonNode json = MAPPER.readTree(result);

        JsonNode seeAlso = json.get("sequences").get(0).get("canvases").get(0).get("seeAlso");
        assertNotNull(seeAlso);
        assertEquals("application/custom+xml", seeAlso.get(0).get("format").asString());
        assertEquals("Text", seeAlso.get(0).get("type").asString());
        assertEquals("http://example.org/profile", seeAlso.get(0).get("profile").asString());
    }

    @Test
    void testEmptyProfileOverrideOmitsProfile() throws Exception {
        Files.writeString(tempDir.resolve("page1.xml"), "<TEI/>");

        String result = ManifestUpdater.updateWithSeeAlsoFiles(V2_MANIFEST, tempDir, null, null, null, null, "");
        JsonNode json = MAPPER.readTree(result);

        JsonNode seeAlso = json.get("sequences").get(0).get("canvases").get(0).get("seeAlso");
        assertNotNull(seeAlso);
        assertEquals("application/tei+xml", seeAlso.get(0).get("format").asString());
        assertNull(seeAlso.get(0).get("profile"));
    }
}
