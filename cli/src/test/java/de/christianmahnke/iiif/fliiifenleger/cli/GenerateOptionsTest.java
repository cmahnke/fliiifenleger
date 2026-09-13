// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.cli;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("generate command options")
class GenerateOptionsTest {

    private Main.GenerateCommand parse(String... args) {
        Main.GenerateCommand cmd = new Main.GenerateCommand();
        new CommandLine(cmd).parseArgs(args);
        return cmd;
    }

    @Test
    @DisplayName("iiif-version defaults to V2")
    void defaultsToV2() {
        Main.GenerateCommand cmd = parse("some.jpg");
        CommandLine cmdLine = new CommandLine(cmd);
        // Default value is applied on parse; re-parse with a dummy file arg.
        assertThat(cmdLine.getCommandSpec().findOption("--iiif-version").defaultValue()).isEqualTo("V2");
    }

    @Test
    @DisplayName("--iiif-version V3 is accepted")
    void v3Accepted() {
        Main.GenerateCommand cmd = parse("--iiif-version", "V3", "some.jpg");
        ImageInfo.IIIFVersion version = cmdLineValue(cmd, "version");
        assertThat(version).isEqualTo(ImageInfo.IIIFVersion.V3);
    }

    @Test
    @DisplayName("--tile-size and --validate-info are accepted")
    void tileSizeAndValidateInfoAccepted() {
        Main.GenerateCommand cmd = parse("--tile-size", "256", "--validate-info", "some.jpg");
        int tileSize = cmdLineValue(cmd, "tileSize");
        boolean validateInfo = cmdLineValue(cmd, "validateInfo");
        assertThat(tileSize).isEqualTo(256);
        assertThat(validateInfo).isTrue();
    }

    @Test
    @DisplayName("validate --schema defaults to auto")
    void validateSchemaDefaultsAuto() {
        Main.ValidateCommand cmd = new Main.ValidateCommand();
        CommandLine commandLine = new CommandLine(cmd);
        commandLine.parseArgs("http://example.org/iiif/info.json", "-o", "out.jpg");
        assertThat(commandLine.getCommandSpec().findOption("--schema").defaultValue()).isEqualTo("auto");
    }

    @SuppressWarnings("unchecked")
    private static <T> T cmdLineValue(Object cmd, String field) {
        try {
            var f = cmd.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return (T) f.get(cmd);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
