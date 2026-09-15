// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ManifestUpdater {

    private static final Logger log = LoggerFactory.getLogger(ManifestUpdater.class);

    public static final String TEI_FORMAT = "application/tei+xml";
    public static final String TEI_PROFILE = "http://tei-c.org";
    public static final String MUSICXML_FORMAT = "application/vnd.recordare.musicxml+xml";
    public static final String MUSICXML_COMPRESSED_FORMAT = "application/vnd.recordare.musicxml";
    public static final String MEI_FORMAT = "application/vnd.mei+xml";
    public static final String GENERIC_XML_FORMAT = "application/xml";
    public static final String DEFAULT_TYPE = "Dataset";

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
        return updateWithSeeAlsoFiles(manifestJson, teiFolder, null, teiBaseUrl, null, null, null);
    }

    /**
     * Update a manifest with seeAlso files from a folder, matched by base name.
     *
     * @param manifestJson The IIIF manifest as JSON string
     * @param folder Path to folder containing files to link as seeAlso
     * @param globPattern Glob pattern for file names (e.g. "*.tei.xml", "*.musicxml").
     *                    If null or empty, legacy behavior is used ({@code *.xml} and {@code *.tei},
     *                    case-insensitive).
     * @param baseUrl Base URL for files (if null, uses manifest base URI)
     * @param formatOverride Media type to use for all files (if null, auto-detected per file)
     * @param typeOverride seeAlso type (if null, defaults to {@code "Dataset"})
     * @param profileOverride seeAlso profile (if null, per-format default is used;
     *                        empty string forces omission)
     * @return Updated manifest JSON
     */
    public static String updateWithSeeAlsoFiles(String manifestJson, Path folder, String globPattern,
                                                String baseUrl, String formatOverride,
                                                String typeOverride, String profileOverride) throws Exception {
        IiifManifest manifest = IiifManifest.fromJson(manifestJson);

        Map<String, String> files = scanFolder(folder, globPattern);

        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = manifest.getBaseUri();
        }
        String type = (typeOverride == null || typeOverride.isEmpty()) ? DEFAULT_TYPE : typeOverride;

        int matched = 0;
        int unmatched = 0;

        for (IiifManifest.CanvasRef canvas : manifest.getCanvases()) {
            String canvasKey = extractCanvasKey(canvas);
            if (files.containsKey(canvasKey)) {
                String filename = files.get(canvasKey);
                String url = baseUrl + "/" + filename;
                String format = resolveFormat(filename, formatOverride);
                String profile = resolveProfile(filename, format, profileOverride);
                IiifManifest.SeeAlsoRef seeAlsoRef = new IiifManifest.SeeAlsoRef(
                        url,
                        type,
                        format,
                        profile
                );
                canvas.addSeeAlso(seeAlsoRef);
                log.info("Added seeAlso for canvas '{}': {} ({})", canvasKey, url, format);
                matched++;
            } else {
                log.warn("No seeAlso file found for canvas '{}'", canvasKey);
                unmatched++;
            }
        }

        log.info("seeAlso matching complete: {} matched, {} unmatched of {} canvases (pattern: {})",
                matched, unmatched, manifest.getCanvases().size(),
                globPattern == null || globPattern.isEmpty() ? "<legacy *.xml, *.tei>" : globPattern);

        return IiifManifest.toPrettyJson(manifest.toJson());
    }

    /**
     * Scan a folder for TEI XML files.
     * Returns map of base name (without extension) to filename.
     */
    static Map<String, String> scanTeiFolder(Path folder) throws Exception {
        return scanFolder(folder, null);
    }

    /**
     * Scan a folder for files matching a glob pattern.
     * Returns map of base name (canvas key) to filename.
     * Matching is case-insensitive and non-recursive.
     */
    static Map<String, String> scanFolder(Path folder, String globPattern) throws Exception {
        Map<String, String> files = new HashMap<>();

        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Not a directory: " + folder);
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    String filename = entry.getFileName().toString();
                    if (matchesGlob(filename, globPattern)) {
                        String baseName = stripSuffix(filename, globPattern);
                        files.put(baseName.toLowerCase(), filename);
                    }
                }
            }
        }

        return files;
    }

    /**
     * Check whether a filename matches the glob pattern (case-insensitive).
     * A null or empty pattern reproduces the legacy TEI filter.
     */
    static boolean matchesGlob(String filename, String globPattern) {
        if (globPattern == null || globPattern.isEmpty()) {
            String lowerFilename = filename.toLowerCase();
            return lowerFilename.endsWith(".xml") || lowerFilename.endsWith(".tei");
        }
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern.toLowerCase());
            return matcher.matches(Path.of(filename.toLowerCase()));
        } catch (Exception e) {
            log.warn("Invalid glob pattern '{}': {}", globPattern, e.getMessage());
            return false;
        }
    }

    /**
     * Strip the literal suffix implied by the glob pattern from a filename.
     * E.g. pattern "*.tei.xml" strips ".tei.xml" so "page1.tei.xml" matches canvas "page1".
     * Falls back to stripping the last extension.
     */
    static String stripSuffix(String filename, String globPattern) {
        if (globPattern != null && !globPattern.isEmpty()) {
            for (String literalSuffix : literalSuffixes(globPattern)) {
                if (!literalSuffix.isEmpty()
                        && filename.length() > literalSuffix.length()
                        && filename.toLowerCase().endsWith(literalSuffix.toLowerCase())) {
                    return filename.substring(0, filename.length() - literalSuffix.length());
                }
            }
        }
        return getBaseName(filename);
    }

    /**
     * Derive literal suffix candidates from a glob pattern.
     * Takes the part after the last wildcard of each brace alternative,
     * e.g. "*.tei.xml" -&gt; [".tei.xml"], "*.{musicxml,mxl}" -&gt; [".musicxml", ".mxl"].
     * Candidates containing wildcards are discarded (fallback applies).
     */
    static List<String> literalSuffixes(String globPattern) {
        List<String> candidates = new ArrayList<>();
        for (String alternative : expandBraces(globPattern)) {
            int lastWildcard = -1;
            for (int i = 0; i < alternative.length(); i++) {
                char c = alternative.charAt(i);
                if (c == '*' || c == '?' || c == '[') {
                    lastWildcard = i;
                }
            }
            String suffix = alternative.substring(lastWildcard + 1);
            if (!suffix.isEmpty() && suffix.indexOf('*') < 0 && suffix.indexOf('?') < 0
                    && suffix.indexOf('[') < 0 && suffix.indexOf(']') < 0) {
                candidates.add(suffix);
            }
        }
        return candidates;
    }

    /**
     * Expand a single brace group, e.g. "*.{musicxml,mxl}" into ["*.musicxml", "*.mxl"].
     * Only the first group is expanded; patterns without braces return as-is.
     */
    static List<String> expandBraces(String glob) {
        int open = glob.indexOf('{');
        int close = glob.indexOf('}');
        if (open < 0 || close < 0 || close < open) {
            return List.of(glob);
        }
        String prefix = glob.substring(0, open);
        String suffix = glob.substring(close + 1);
        String[] options = glob.substring(open + 1, close).split(",");
        List<String> expanded = new ArrayList<>();
        for (String option : options) {
            expanded.add(prefix + option + suffix);
        }
        return expanded;
    }

    /**
     * Resolve the media type for a file.
     * An explicit override wins; otherwise the type is detected from the extension.
     */
    static String resolveFormat(String filename, String formatOverride) {
        if (formatOverride != null && !formatOverride.isEmpty()) {
            return formatOverride;
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".mxl")) {
            return MUSICXML_COMPRESSED_FORMAT;
        }
        if (lower.endsWith(".musicxml")) {
            return MUSICXML_FORMAT;
        }
        if (lower.endsWith(".mei")) {
            return MEI_FORMAT;
        }
        if (lower.endsWith(".tei") || lower.endsWith(".tei.xml") || lower.endsWith(".xml")) {
            return TEI_FORMAT;
        }
        return GENERIC_XML_FORMAT;
    }

    /**
     * Resolve the seeAlso profile for a file.
     * An explicit override wins (empty string forces omission);
     * otherwise per-format defaults apply (TEI -&gt; TEI profile, others omitted).
     */
    static String resolveProfile(String filename, String format, String profileOverride) {
        if (profileOverride != null) {
            return profileOverride.isEmpty() ? null : profileOverride;
        }
        if (TEI_FORMAT.equals(format)) {
            return TEI_PROFILE;
        }
        return null;
    }

    /**
     * Extract the base name from a filename (without extension).
     */
    static String getBaseName(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(0, lastDot) : filename;
    }

    /**
     * Extract a key from canvas for matching with files.
     * Uses label or ID, converted to lowercase for case-insensitive matching.
     */
    static String extractCanvasKey(IiifManifest.CanvasRef canvas) {
        String key = canvas.label != null ? canvas.label : canvas.id;
        return key != null ? key.toLowerCase().trim() : "";
    }
}
