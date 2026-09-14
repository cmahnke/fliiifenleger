// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ManifestUpdater {

    private static final Logger log = LoggerFactory.getLogger(ManifestUpdater.class);

    /**
     * Update a manifest with TEI files from a folder.
     * TEI files are matched to canvases by base name (without extension).
     *
     * @param manifestJson The IIIF manifest as JSON string
     * @param teiFolder Path to folder containing TEI XML files
     * @param teiBaseUrl Base URL for TEI files (if null, uses manifest base URI)
     * @return Updated manifest JSON
     */
    public static String updateWithTeiFiles(String manifestJson, Path teiFolder, String teiBaseUrl) throws Exception {
        IiifManifest manifest = IiifManifest.fromJson(manifestJson);

        Map<String, String> teiFiles = scanTeiFolder(teiFolder);

        if (teiBaseUrl == null || teiBaseUrl.isEmpty()) {
            teiBaseUrl = manifest.getBaseUri();
        }

        int matched = 0;
        int unmatched = 0;

        for (IiifManifest.CanvasRef canvas : manifest.getCanvases()) {
            String canvasKey = extractCanvasKey(canvas);
            if (teiFiles.containsKey(canvasKey)) {
                String teiUrl = teiBaseUrl + "/" + teiFiles.get(canvasKey);
                IiifManifest.SeeAlsoRef seeAlsoRef = new IiifManifest.SeeAlsoRef(
                        teiUrl,
                        "Dataset",
                        "application/tei+xml",
                        "http://tei-c.org"
                );
                canvas.addSeeAlso(seeAlsoRef);
                log.info("Added TEI seeAlso for canvas '{}': {}", canvasKey, teiUrl);
                matched++;
            } else {
                log.warn("No TEI file found for canvas '{}'", canvasKey);
                unmatched++;
            }
        }

        log.info("TEI matching complete: {} matched, {} unmatched of {} canvases", matched, unmatched, manifest.getCanvases().size());

        return IiifManifest.toPrettyJson(manifest.toJson());
    }

    /**
     * Scan a folder for TEI XML files.
     * Returns map of base name (without extension) to filename.
     */
    static Map<String, String> scanTeiFolder(Path folder) throws Exception {
        Map<String, String> teiFiles = new HashMap<>();

        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Not a directory: " + folder);
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    String filename = entry.getFileName().toString();
                    String lowerFilename = filename.toLowerCase();
                    if (lowerFilename.endsWith(".xml") || lowerFilename.endsWith(".tei")) {
                        String baseName = getBaseName(filename);
                        teiFiles.put(baseName.toLowerCase(), filename);
                    }
                }
            }
        }

        return teiFiles;
    }

    /**
     * Extract the base name from a filename (without extension).
     */
    static String getBaseName(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(0, lastDot) : filename;
    }

    /**
     * Extract a key from canvas for matching with TEI files.
     * Uses label or ID, converted to lowercase for case-insensitive matching.
     */
    static String extractCanvasKey(IiifManifest.CanvasRef canvas) {
        String key = canvas.label != null ? canvas.label : canvas.id;
        return key != null ? key.toLowerCase().trim() : "";
    }
}
