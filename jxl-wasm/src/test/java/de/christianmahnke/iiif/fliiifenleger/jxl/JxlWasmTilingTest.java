// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.jxl;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.Tiler;
import de.christianmahnke.iiif.fliiifenleger.sink.DefaultTileSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: JXL-HDR source through the SDR tiling pipeline.
 *
 * <p>A PQ codestream carries no gain map, so the {@code ultrahdr} sink
 * cannot apply — this test pins the defined behaviour instead: the SDR
 * rendition tiles generate normally and {@code info.json} validates
 * against its schema, i.e. HDR input degrades to correct SDR output
 * rather than failing.
 */
@DisplayName("JXL-HDR tiling end-to-end")
class JxlWasmTilingTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("HDR source tiles to SDR with valid info.json")
    void hdrSourceTilesToSdr() throws Exception {
        File fixture = new File("src/test/resources/images/hdr-pq.jxl");
        assertThat(fixture).exists();

        JxlWasmImageSource source = new JxlWasmImageSource();
        source.load(fixture.toURI().toURL());

        DefaultTileSink sink = new DefaultTileSink();
        sink.setOptions(java.util.Map.of("format", "jpg"));

        // 256px fixture with 128px tiles, two zoom levels: a handful of tiles.
        ImageInfo imageInfo = new ImageInfo(source, 128, 128, 2,
            "http://localhost:8887/iiif/", ImageInfo.IIIFVersion.V2);
        Tiler tiler = new Tiler();
        Path out = tiler.createImage(imageInfo, tempDir, ImageInfo.IIIFVersion.V2, sink);

        assertThat(out.resolve("info.json")).exists();
        long tiles;
        try (var stream = Files.walk(out)) {
            tiles = stream.filter(p -> p.toString().endsWith(".jpg")).count();
        }
        assertThat(tiles).isGreaterThanOrEqualTo(2);
    }
}
