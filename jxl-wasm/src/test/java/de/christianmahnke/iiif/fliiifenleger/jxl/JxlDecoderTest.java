// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/test/java/de/christianmahnke/iiif/fliiifenleger/jxl/JxlDecoderTest.java
package de.christianmahnke.iiif.fliiifenleger.jxl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link JxlDecoder} against a tiny 64x64 fixture.
 *
 * <p>Pins the Chicory engine explicitly, so the test executes identically
 * on every JVM — including stock CI runners.  The fixture is small enough
 * for the interpreter (~3 s); larger inputs are covered by the manual
 * Chicory-vs-GraalWasm spike instead.
 */
@DisplayName("JxlDecoder")
class JxlDecoderTest {

    /** 64x64 lossless-transcoded JPEG XL (exercises the JPEG-bitstream path). */
    private static byte[] fixtureBytes() throws Exception {
        try (InputStream is = JxlDecoderTest.class.getResourceAsStream("/images/small.jxl")) {
            assertThat(is).as("test fixture /images/small.jxl").isNotNull();
            return is.readAllBytes();
        }
    }

    @Test
    @DisplayName("decode returns dimensions and non-empty pixels")
    void decodeFixture() throws Exception {
        try (JxlDecoder decoder = new JxlDecoder("chicory", 1)) {
            JxlDecoder.DecodedImage image = decoder.decode(fixtureBytes());
            assertThat(image.width()).isEqualTo(64);
            assertThat(image.height()).isEqualTo(64);
            assertThat(image.channels()).isEqualTo(3);
            assertThat(image.pixels()).hasSize(64 * 64 * 3);
            long nonZero = 0;
            for (byte b : image.pixels()) {
                if (b != 0) {
                    nonZero++;
                }
            }
            assertThat(nonZero).as("non-zero pixel bytes")
                .isGreaterThan(image.pixels().length / 2L);
        }
    }

    @Test
    @DisplayName("codec version reports the bundled jxl-oxide")
    void codecVersion() throws Exception {
        try (JxlDecoder decoder = new JxlDecoder("chicory", 1)) {
            assertThat(decoder.codecVersion()).matches("\\d+\\.\\d+(\\.\\d+)?");
        }
    }

    @Test
    @DisplayName("garbage input fails with a JxlWasmException")
    void garbageInput() throws Exception {
        try (JxlDecoder decoder = new JxlDecoder("chicory", 1)) {
            assertThatThrownBy(() -> decoder.decode(new byte[64]))
                .isInstanceOf(JxlWasmException.class);
        }
    }

    @Test
    @DisplayName("lane count follows the requested parallelism")
    void laneCount() throws Exception {
        try (JxlDecoder serial = new JxlDecoder("chicory", 1)) {
            assertThat(serial.laneCount()).isEqualTo(1);
        }
    }
}
