// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.cli;

import de.christianmahnke.iiif.fliiifenleger.IiifManifest;
import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("validate --manifest")
class ValidateManifestTest {

    private Main.ValidateCommand parse(String... args) {
        Main.ValidateCommand cmd = new Main.ValidateCommand();
        new CommandLine(cmd).parseArgs(args);
        return cmd;
    }

    @Test
    @DisplayName("valid V4 manifest passes without --output")
    void validManifest(@TempDir Path tempDir) throws Exception {
        IiifManifest manifest = new IiifManifest(ImageInfo.IIIFVersion.V4, "http://example.org/iiif/book", "Book");
        manifest.addCanvas("p1", "Page 1", "http://example.org/iiif/book/image", 800, 600);
        Path file = tempDir.resolve("manifest.json");
        Files.writeString(file, IiifManifest.toPrettyJson(manifest.toJson()));

        Main.ValidateCommand cmd = parse("--manifest", "--manifest-version", "4", file.toString());

        assertThat(cmd.call()).isZero();
    }

    @Test
    @DisplayName("invalid manifest fails")
    void invalidManifest(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("manifest.json");
        Files.writeString(file, "{\"type\":\"Manifest\"}");

        Main.ValidateCommand cmd = parse("--manifest", file.toString());

        assertThat(cmd.call()).isEqualTo(1);
    }

    @Test
    @DisplayName("image mode without --output fails")
    void imageModeNeedsOutput() {
        Main.ValidateCommand cmd = parse("http://example.org/iiif/info.json");
        assertThat(cmd.call()).isEqualTo(1);
    }

    @Test
    @DisplayName("generate rejects --iiif-version V4")
    void generateRejectsV4() throws Exception {
        Main.GenerateCommand cmd = new Main.GenerateCommand();
        new CommandLine(cmd).parseArgs("--iiif-version", "V4", "image.jpg");
        assertThat(cmd.call()).isEqualTo(1);
    }
}
