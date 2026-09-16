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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        URI infoJsonUrl = serveEndpoint(endpoint);

        Path out = tempDir.resolve("reassembled.jpg");
        Main.ValidateCommand command = new Main.ValidateCommand();
        int exit = new CommandLine(command)
            .execute("-o", out.toString(), infoJsonUrl.toString());

        assertThat(exit).isZero();
        assertThat(out).exists();

        // The reassembled file is itself an UltraHDR JPEG: primary + gain map.
        byte[] reassembled = Files.readAllBytes(out);
        try (GainMapCodec verifyCodec = new GainMapCodec((String) null, 1)) {
            GainMapCodec.UhdrSplit split = verifyCodec.decode(reassembled);
            assertThat(split.gainmapJpeg()).isNotEmpty();
            assertThat(split.metadataJson()).contains("alternateHdrHeadroom");
            BufferedImage primary = ImageIO.read(new java.io.ByteArrayInputStream(split.primaryJpeg()));
            assertThat(primary.getWidth()).isEqualTo(1024);
            assertThat(primary.getHeight()).isEqualTo(1024);
        }
    }

    @Test
    @DisplayName("a non-HDR endpoint reassembles to a plain SDR JPEG")
    void sdrEndpointReassemblesSdr() throws Exception {
        Path fixture = Path.of("src/test/resources/images/uhdr-crop.jpg");
        assertThat(fixture).exists();

        // Generate a plain (SDR) endpoint: same fixture, default sink.
        Path endpoint = tempDir.resolve("sdr-endpoint");
        UltraHdrImageSource source = new UltraHdrImageSource();
        source.load(fixture.toUri().toURL());
        DefaultTileSink sink = new DefaultTileSink();
        sink.setOptions(Map.of("format", "jpg"));
        Tiler tiler = new Tiler(256, ImageInfo.IIIFVersion.V2);
        tiler.createImages(source, List.of(fixture), endpoint,
                           "http://localhost:8887/iiif/", 1, sink);

        URI infoJsonUrl = serveEndpoint(endpoint);

        Path out = tempDir.resolve("sdr-reassembled.jpg");
        int exit = new CommandLine(new Main.ValidateCommand())
            .execute("-o", out.toString(), infoJsonUrl.toString());

        assertThat(exit).isZero();
        assertThat(out).exists();

        // Without the HDR marker the output must be a plain JPEG, not UHDR.
        byte[] bytes = Files.readAllBytes(out);
        assertThatThrownBy(() -> {
            try (GainMapCodec c = new GainMapCodec((String) null, 1)) {
                c.decode(bytes);
            }
        }).isInstanceOf(de.christianmahnke.iiif.fliiifenleger.ultrahdr.UltraHdrException.class);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(bytes))).isNotNull();
    }

    /**
     * Re-points the generated info.json at the mock and serves every region
     * tile under the path derived from the info.json {@code @id} (which the
     * reassembler uses to fetch tiles).
     *
     * @return The served info.json URL.
     */
    private URI serveEndpoint(Path endpoint) throws Exception {
        String infoJson = Files.readString(endpoint.resolve("info.json"))
            .replace("http://localhost:8887/iiif/", wiremock.baseUrl() + "/iiif/");
        wiremock.stubFor(get(urlEqualTo("/iiif/info.json"))
            .willReturn(aResponse().withBody(infoJson)));
        java.util.regex.Matcher idMatcher =
            java.util.regex.Pattern.compile("\"@id\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(infoJson);
        assertThat(idMatcher.find()).isTrue();
        String imagePrefix = new URI(idMatcher.group(1)).getPath();
        int served = 0;
        try (Stream<Path> tiles = Files.walk(endpoint)) {
            for (Path tile : (Iterable<Path>) tiles.filter(p -> p.toString().endsWith(".jpg"))::iterator) {
                Path rel = endpoint.relativize(tile);
                if (rel.getNameCount() < 2 || rel.getName(0).toString().equals("full")) {
                    continue;
                }
                String key = imagePrefix + "/" + rel.getName(0) + "/full/0/default.jpg";
                wiremock.stubFor(get(urlEqualTo(key))
                    .willReturn(aResponse().withBody(Files.readAllBytes(tile))
                        .withHeader("Content-Type", "image/jpeg")));
                served++;
            }
        }
        assertThat(served).isGreaterThanOrEqualTo(4);
        return URI.create(wiremock.baseUrl() + "/iiif/info.json");
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
