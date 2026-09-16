// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.cli;

import de.christianmahnke.jc2pa.TileSigner;
import de.christianmahnke.iiif.fliiifenleger.sink.DefaultTileSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
import java.util.List;
import java.util.Map;

import picocli.CommandLine;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI-level tests for {@code validate --check-c2pa --trust-anchor}.
 *
 * <p>The signing chain is generated with the {@code openssl} CLI (same
 * recipe as the README): a throwaway CA plus end-entity certificate.
 * Skipped where {@code openssl} is unavailable.
 */
@DisplayName("validate --trust-anchor")
class ValidateTrustAnchorTest {

    private static final int SIZE = 256;

    private static WireMockServer wiremock;
    private static Path workDir;
    private static Path chainPem;
    private static Path keyPem;
    private static Path caPem;
    private static Path otherCaPem;

    @TempDir
    static Path sharedTempDir;

    @TempDir
    Path tempDir;

    private static void openssl(String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("openssl");
        cmd.addAll(List.of(args));
        Process process = new ProcessBuilder(cmd)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        Assumptions.assumeTrue(exit == 0, "openssl failed: " + output);
    }

    private static void makeChain(String prefix, String cn) throws Exception {
        // Self-signed CA.
        openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout",
                "-out", prefix + "-ca-key.pem");
        openssl("req", "-x509", "-new", "-nodes", "-key", prefix + "-ca-key.pem",
                "-sha256", "-days", "2",
                "-subj", "/CN=" + cn + "-test-ca/O=" + cn + "-test",
                "-addext", "basicConstraints=critical,CA:true",
                "-addext", "keyUsage=critical,keyCertSign,digitalSignature,cRLSign",
                "-addext", "subjectKeyIdentifier=hash",
                "-addext", "authorityKeyIdentifier=keyid:always",
                "-out", prefix + "-ca-cert.pem");
        // End-entity, CA-signed (PKCS#8 key, chain file EE-first).
        openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout",
                "-out", prefix + "-ee-sec1.pem");
        openssl("pkcs8", "-topk8", "-nocrypt", "-in", prefix + "-ee-sec1.pem",
                "-out", prefix + "-key.pem");
        openssl("req", "-new", "-key", prefix + "-key.pem",
                "-subj", "/CN=" + cn + "-test/O=" + cn + "-test",
                "-out", prefix + "-ee.csr");
        Files.writeString(workDir.resolve(prefix + "-ee-ext.cnf"),
            "basicConstraints=critical,CA:false\n"
            + "keyUsage=critical,digitalSignature\n"
            + "extendedKeyUsage=emailProtection\n"
            + "subjectKeyIdentifier=hash\n"
            + "authorityKeyIdentifier=keyid,issuer\n");
        openssl("x509", "-req", "-in", prefix + "-ee.csr",
                "-CA", prefix + "-ca-cert.pem", "-CAkey", prefix + "-ca-key.pem",
                "-CAcreateserial", "-days", "2", "-sha256",
                "-extfile", prefix + "-ee-ext.cnf",
                "-out", prefix + "-ee-cert.pem");
        // Chain file: end-entity first, then CA.
        String chain = Files.readString(workDir.resolve(prefix + "-ee-cert.pem"))
            + Files.readString(workDir.resolve(prefix + "-ca-cert.pem"));
        Files.writeString(workDir.resolve(prefix + "-chain.pem"), chain);
    }

    @BeforeAll
    static void setUp() throws Exception {
        try {
            Process probe = new ProcessBuilder("openssl", "version").start();
            Assumptions.assumeTrue(probe.waitFor() == 0, "openssl CLI required");
        } catch (Exception e) {
            Assumptions.abort("openssl CLI required: " + e.getMessage());
        }
        workDir = sharedTempDir;
        makeChain("good", "fliiifenleger-trust");
        makeChain("other", "fliiifenleger-other");
        chainPem = workDir.resolve("good-chain.pem");
        keyPem = workDir.resolve("good-key.pem");
        caPem = workDir.resolve("good-ca-cert.pem");
        otherCaPem = workDir.resolve("other-ca-cert.pem");

        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void tearDown() {
        if (wiremock != null) {
            wiremock.stop();
        }
    }

    private byte[] signedTile() throws Exception {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(10, 200, 90));
            g.fillRect(0, 0, SIZE, SIZE);
        } finally {
            g.dispose();
        }
        DefaultTileSink delegate = new DefaultTileSink();
        delegate.setOptions(Map.of("format", "jpg"));
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        delegate.saveTile(plain, image, null);
        String manifest = """
            {
              "claim_generator": "fliiifenleger-test/1.0",
              "title": "Trust Test Tile",
              "assertions": [
                {"label": "c2pa.actions",
                 "data": {"actions": [{"action": "c2pa.created",
                                       "digitalSourceType": "http://c2pa.org/digitalsourcetype/empty"}]}}
              ]
            }
            """;
        try (TileSigner signer = new TileSigner((String) null)) {
            return signer.sign(plain.toByteArray(), "image/jpeg", manifest,
                               Files.readAllBytes(chainPem), Files.readAllBytes(keyPem),
                               "es256", null);
        }
    }

    private void serveTile(byte[] tile) {
        String infoJson = """
            {
              "@context": "http://iiif.io/api/image/2/context.json",
              "@id": "%s",
              "@type": "iiif:Image",
              "protocol": "http://iiif.io/api/image",
              "width": %d,
              "height": %d,
              "tiles": [{"width": %d, "scaleFactors": [1]}],
              "profile": ["http://iiif.io/api/image/2/level0.json"]
            }
            """.formatted(wiremock.baseUrl() + "/iiif", SIZE, SIZE, SIZE);
        wiremock.stubFor(get(urlEqualTo("/iiif/info.json"))
            .willReturn(aResponse().withBody(infoJson)));
        wiremock.stubFor(get(urlEqualTo("/iiif/0,0,256,256/full/0/default.jpg"))
            .willReturn(aResponse().withBody(tile).withHeader("Content-Type", "image/jpeg")));
    }

    private int validate(Path anchor) {
        Main.ValidateCommand command = new Main.ValidateCommand();
        List<String> args = new java.util.ArrayList<>(List.of(
            "-o", tempDir.resolve("reassembled.jpg").toString(), "--check-c2pa"));
        if (anchor != null) {
            args.add("--trust-anchor");
            args.add(anchor.toString());
        }
        args.add(URI.create(wiremock.baseUrl() + "/iiif/info.json").toString());
        return new CommandLine(command).execute(args.toArray(new String[0]));
    }

    @Test
    @DisplayName("matching anchor validates as trusted → exit 0")
    void matchingAnchorTrusted() throws Exception {
        serveTile(signedTile());
        assertThat(validate(caPem)).isZero();
    }

    @Test
    @DisplayName("foreign anchor is not trusted → exit 2")
    void foreignAnchorUntrusted() throws Exception {
        serveTile(signedTile());
        assertThat(validate(otherCaPem)).isEqualTo(2);
    }
}
