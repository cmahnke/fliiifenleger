// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.cli;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.Tiler;
import de.christianmahnke.iiif.fliiifenleger.sink.DefaultTileSink;
import de.christianmahnke.iiif.fliiifenleger.ultrahdr.GainMapCodec;
import de.christianmahnke.iiif.fliiifenleger.ultrahdr.UltraHdrImageSource;
import de.christianmahnke.iiif.fliiifenleger.ultrahdr.UltraHdrTileSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import picocli.CommandLine;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates a served HDR (UltraHDR gain-map) endpoint end to end.
 *
 * <p>The endpoint is generated from a real camera gain-map fixture
 * ({@code uhdr-crop.jpg}, see test resources NOTICE), served tile by
 * tile over WireMock with the generated {@code info.json} re-pointed
 * at the mock base URL.  This answers: does reassembly work when
 * every tile is a gain-map JPEG?
 */
@DisplayName("validate HDR endpoint")
class ValidateHdrTest {

    private static WireMockServer wiremock;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setUp() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void tearDown() {
        if (wiremock != null) {
            wiremock.stop();
        }
    }

    @Test
    @DisplayName("HDR endpoint reassembles with matching dimensions")
    void hdrEndpointReassembles() throws Exception {
        Path fixture = Path.of("src/test/resources/images/uhdr-crop.jpg");
        assertThat(fixture).exists();

        // Generate a small HDR endpoint: 1024px source, 256px tiles.
        Path endpoint = tempDir.resolve("endpoint");
        try (UltraHdrTileSink sink = new UltraHdrTileSink()) {
            sink.setOptions(Map.of("format", "jpg", "threads", "1"));
            UltraHdrImageSource source = new UltraHdrImageSource();
            source.load(fixture.toUri().toURL());
            Tiler tiler = new Tiler(256, ImageInfo.IIIFVersion.V2);
            tiler.createImages(source, List.of(fixture), endpoint,
                               "http://localhost:8887/iiif/", 1, sink);
        }

        // Re-point the generated info.json at the mock and serve every tile.
        // The reassembler always requests region tiles at full size
        // ({region}/full/0/...), so map each generated region directory to
        // its full-size URL (the on-disk size variants are never fetched).
        String infoJson = Files.readString(endpoint.resolve("info.json"))
            .replace("http://localhost:8887/iiif/", wiremock.baseUrl() + "/iiif/");
        wiremock.stubFor(get(urlEqualTo("/iiif/info.json"))
            .willReturn(aResponse().withBody(infoJson)));
        int served = 0;
        try (Stream<Path> tiles = Files.walk(endpoint)) {
            for (Path tile : (Iterable<Path>) tiles.filter(p -> p.toString().endsWith(".jpg"))::iterator) {
                Path rel = endpoint.relativize(tile);
                if (rel.getNameCount() < 2 || rel.getName(0).toString().equals("full")) {
                    continue;
                }
                String key = "/iiif/" + rel.getName(0) + "/full/0/default.jpg";
                wiremock.stubFor(get(urlEqualTo(key))
                    .willReturn(aResponse().withBody(Files.readAllBytes(tile))
                        .withHeader("Content-Type", "image/jpeg")));
                served++;
            }
        }
        assertThat(served).isGreaterThanOrEqualTo(4);

        Path out = tempDir.resolve("reassembled.jpg");
        Main.ValidateCommand command = new Main.ValidateCommand();
        int exit = new CommandLine(command)
            .execute("-o", out.toString(),
                     URI.create(wiremock.baseUrl() + "/iiif/info.json").toString());

        assertThat(exit).isZero();
        assertThat(out).exists();
        BufferedImage reassembled = ImageIO.read(out.toFile());
        assertThat(reassembled.getWidth()).isEqualTo(1024);
        assertThat(reassembled.getHeight()).isEqualTo(1024);
    }

    @Test
    @DisplayName("served tiles still carry their gain maps")
    void servedTilesCarryGainMaps() throws Exception {
        Path tile = Path.of("src/test/resources/images/uhdr-crop.jpg");
        // Sanity: the fixture itself splits (cheaper than serving every tile).
        byte[] bytes = Files.readAllBytes(tile);
        // Engine follows the wasm.engine property (graalvm under -Pnative).
        try (GainMapCodec codec = new GainMapCodec((String) null, 1)) {
            GainMapCodec.UhdrSplit split = codec.decode(bytes);
            assertThat(split.gainmapJpeg()).isNotEmpty();
        }
    }
}
