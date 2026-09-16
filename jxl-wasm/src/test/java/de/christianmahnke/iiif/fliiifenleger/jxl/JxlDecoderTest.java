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

    /** 256x256 Rec.2100 PQ fixture (synthetic HDR scene, see NOTICE). */
    private static byte[] hdrFixtureBytes() throws Exception {
        try (InputStream is = JxlDecoderTest.class.getResourceAsStream("/images/hdr-pq.jxl")) {
            assertThat(is).as("test fixture /images/hdr-pq.jxl").isNotNull();
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

    @Test
    @DisplayName("decodeHdr reports PQ transfer and full-range floats")
    void decodeHdrFixture() throws Exception {
        try (JxlDecoder decoder = new JxlDecoder("chicory", 1)) {
            de.christianmahnke.iiif.fliiifenleger.source.HdrFrame frame =
                decoder.decodeHdr(hdrFixtureBytes());
            assertThat(frame.width()).isEqualTo(256);
            assertThat(frame.height()).isEqualTo(256);
            assertThat(frame.channels()).isEqualTo(3);
            assertThat(frame.transfer()).isEqualTo(
                de.christianmahnke.iiif.fliiifenleger.source.HdrFrame.TransferFunction.PQ);
            assertThat(frame.primaries()).isEqualTo(
                de.christianmahnke.iiif.fliiifenleger.source.HdrFrame.Primaries.BT2020);
            assertThat(frame.pixels()).hasSize(256 * 256 * 3);
            float max = 0f;
            for (float v : frame.pixels()) {
                max = Math.max(max, v);
            }
            // Native PQ codes of the synthetic highlights (see the Rust
            // test for the linear-space equivalent assertion).
            assertThat(max).isGreaterThan(0.5f).isLessThanOrEqualTo(1.0f);
        }
    }

    @Test
    @DisplayName("bridge exposes the HDR capability with matching dimensions")
    void bridgeHdrCapability() throws Exception {
        JxlWasmImageSource source = new JxlWasmImageSource();
        java.io.File fixture = new java.io.File("src/test/resources/images/hdr-pq.jxl");
        org.junit.jupiter.api.Assertions.assertTrue(fixture.exists(), "HDR fixture must exist");
        source.load(fixture.toURI().toURL());
        assertThat(source.getWidth()).isEqualTo(256);
        assertThat(source.getHeight()).isEqualTo(256);
        de.christianmahnke.iiif.fliiifenleger.source.HdrFrame frame = source.getHdrFrame();
        assertThat(frame.width()).isEqualTo(source.getWidth());
        assertThat(frame.height()).isEqualTo(source.getHeight());
        // SDR rendition stays usable alongside the HDR frame.
        assertThat(source.getImage().getWidth()).isEqualTo(256);
    }
}
