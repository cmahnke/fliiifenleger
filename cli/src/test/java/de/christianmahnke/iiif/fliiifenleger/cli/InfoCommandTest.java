// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("info command introspection")
class InfoCommandTest {

    private PrintStream originalOut;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    private String execute(String... args) {
        int exit = new CommandLine(new Main()).execute(args);
        assertThat(exit).isZero();
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("list-sources names the bundled sources")
    void listSources() {
        String out = execute("info", "list-sources");
        assertThat(out).contains("Available image sources:");
        assertThat(out).contains("default");
    }

    @Test
    @DisplayName("list-sinks names the default sink")
    void listSinks() {
        String out = execute("info", "list-sinks");
        assertThat(out).contains("Available image sinks:");
        assertThat(out).contains("default");
    }

    @Test
    @DisplayName("describe-sink shows option name, default and type hint")
    void describeSinkShowsDefaultAndType() {
        String out = execute("info", "describe-sink", "default");
        assertThat(out).contains("format");
        // printOptions renders "- <name> <type>" plus "[default: ...]".
        assertThat(out).contains("<string>");
        assertThat(out).contains("jpg");
    }

    @Test
    @DisplayName("describe-source on unknown name fails with exit code 1")
    void describeUnknownSourceFails() {
        int exit = new CommandLine(new Main()).execute("info", "describe-source", "does-not-exist");
        assertThat(exit).isEqualTo(1);
    }

    @Test
    @DisplayName("list-validators names the bundled validators")
    void listValidators() {
        String out = execute("info", "list-validators");
        assertThat(out).contains("Available info.json validators:");
    }
}
