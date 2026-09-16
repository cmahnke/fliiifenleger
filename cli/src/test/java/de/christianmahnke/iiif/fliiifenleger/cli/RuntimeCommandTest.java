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

@DisplayName("runtime command")
class RuntimeCommandTest {

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

    @Test
    @DisplayName("runtime reports WASM engines and JXL backends")
    void runtimeReportsEnginesAndBackends() {
        int exit = new CommandLine(new Main()).execute("runtime");
        assertThat(exit).isZero();
        String out = captured.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("JVM:");
        assertThat(out).contains("WASM engines:");
        assertThat(out).contains("JXL backends:");
        assertThat(out).contains("registered 'jxl' source:");
    }
}
