/**
 * Fliiifenleger
 * Copyright (C) 2026  Christian Mahnke
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.christianmahnke.iiif.fliiifenleger.cli;

import de.christianmahnke.jc2pa.TileSigner;
import de.christianmahnke.iiif.fliiifenleger.sink.DefaultTileSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import picocli.CommandLine;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI-level tests for the {@code validate --check-c2pa} command, served by
 * a WireMock IIIF endpoint.
 *
 * <p>The signed test tiles are produced with a dedicated {@link TileSigner}
 * (one live WASM instance per JVM — c2pa-rs keeps process-global state).
 */
@DisplayName("validate --check-c2pa")
class ValidateC2paTest {

    private static final int FULL_W = 512;
    private static final int FULL_H = 256;
    private static final int TILE   = 256;

    private static WireMockServer wiremock;
    private static byte[] signedTile;
    private static byte[] unsignedTile;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        // Produce one C2PA-signed tile and one unsigned tile.
        BufferedImage image = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(120, 40, 200));
            g.fillRect(0, 0, TILE, TILE);
        } finally {
            g.dispose();
        }

        DefaultTileSink delegate = new DefaultTileSink();
        delegate.setOptions(Map.of("format", "jpg"));
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        delegate.saveTile(plain, image, null);
        unsignedTile = plain.toByteArray();

        try (TileSigner signer = new TileSigner((String) null)) {
            signedTile = signer.signEphemeral(unsignedTile, "image/jpeg",
                """
                {
                  "claim_generator": "validate-test/1.0",
                  "title": "Validate Test Tile",
                  "assertions": []
                }
                """, "validate-test");
        }
        // The signer (and its WASM instance) is closed here; the CLI command
        // under test creates its own instance afterwards.

        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void tearDown() {
        if (wiremock != null) {
            wiremock.stop();
        }
    }

    private void serveEndpoint(String tilePath1, byte[] tile1,
                               String tilePath2, byte[] tile2) {
        String infoJson = """
            {
              "@id": "%s",
              "@type": "iiif:Image",
              "protocol": "http://iiif.io/api/image",
              "width": %d,
              "height": %d,
              "tiles": [{"width": %d, "scaleFactors": [1]}],
              "profile": "http://iiif.io/api/image/2/level0.json"
            }
            """.formatted(wiremock.baseUrl() + "/iiif", FULL_W, FULL_H, TILE);

        wiremock.stubFor(get(urlEqualTo("/iiif/info.json"))
            .willReturn(aResponse().withBody(infoJson)));
        wiremock.stubFor(get(urlEqualTo(tilePath1))
            .willReturn(aResponse().withBody(tile1).withHeader("Content-Type", "image/jpeg")));
        wiremock.stubFor(get(urlEqualTo(tilePath2))
            .willReturn(aResponse().withBody(tile2).withHeader("Content-Type", "image/jpeg")));
    }

    @Test
    @DisplayName("all tiles signed → exit 0")
    void allTilesSigned() throws Exception {
        serveEndpoint("/iiif/0,0,256,256/full/0/default.jpg", signedTile,
                      "/iiif/256,0,256,256/full/0/default.jpg", signedTile);

        Path out = tempDir.resolve("reassembled.jpg");
        Main.ValidateCommand command = new Main.ValidateCommand();
        int exit = new CommandLine(command)
            .execute("-o", out.toString(), "--check-c2pa",
                     URI.create(wiremock.baseUrl() + "/iiif/info.json").toString());

        assertThat(exit).isZero();
        assertThat(out).exists();
    }

    @Test
    @DisplayName("one unsigned tile → exit 2")
    void oneUnsignedTile() throws Exception {
        serveEndpoint("/iiif/0,0,256,256/full/0/default.jpg", signedTile,
                      "/iiif/256,0,256,256/full/0/default.jpg", unsignedTile);

        Path out = tempDir.resolve("reassembled.jpg");
        Main.ValidateCommand command = new Main.ValidateCommand();
        int exit = new CommandLine(command)
            .execute("-o", out.toString(), "--check-c2pa",
                     URI.create(wiremock.baseUrl() + "/iiif/info.json").toString());

        assertThat(exit).isEqualTo(2);
        assertThat(out).exists();
    }

    @Test
    @DisplayName("without --check-c2pa the reassembly alone succeeds")
    void withoutCheckSucceeds() throws Exception {
        serveEndpoint("/iiif/0,0,256,256/full/0/default.jpg", unsignedTile,
                      "/iiif/256,0,256,256/full/0/default.jpg", unsignedTile);

        Path out = tempDir.resolve("reassembled.jpg");
        Main.ValidateCommand command = new Main.ValidateCommand();
        int exit = new CommandLine(command)
            .execute("-o", out.toString(),
                     URI.create(wiremock.baseUrl() + "/iiif/info.json").toString());

        assertThat(exit).isZero();
        assertThat(out).exists();
        BufferedImage reassembled = ImageIO.read(out.toFile());
        assertThat(reassembled.getWidth()).isEqualTo(FULL_W);
        assertThat(reassembled.getHeight()).isEqualTo(FULL_H);
    }
}
