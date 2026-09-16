// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UltraHdrAssembler")
class UltraHdrAssemblerTest {

    private static GainMapCodec codec;

    private static final String METADATA_JSON = """
        {
          "gainMapMax": [2.0, 2.0, 2.0],
          "gainMapMin": [0.0, 0.0, 0.0],
          "gamma": [1.0, 1.0, 1.0],
          "baseOffset": [0.015625, 0.015625, 0.015625],
          "alternateOffset": [0.015625, 0.015625, 0.015625],
          "baseHdrHeadroom": 0.0,
          "alternateHdrHeadroom": 1.0,
          "useBaseColorSpace": true,
          "backwardDirection": false
        }
        """;

    @BeforeAll
    static void setUp() throws Exception {
        codec = GainMapCodec.shared("chicory");
    }

    @AfterAll
    static void tearDown() {
        if (codec != null) {
            codec.close();
        }
    }

    private static byte[] jpeg(int w, int h, Color color) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static BufferedImage solid(int w, int h, Color color) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        return image;
    }

    @Test
    @DisplayName("two halved UHDR tiles assemble to a full HDR image")
    void assemblesHalvedTiles() throws Exception {
        // Left half: primary 32x32, gain map 16x16 (2:1). Right half the same.
        byte[] left = codec.encode(jpeg(32, 32, Color.RED), jpeg(16, 16, Color.RED),
                                   METADATA_JSON, 90, 85);
        byte[] right = codec.encode(jpeg(32, 32, Color.BLUE), jpeg(16, 16, Color.BLUE),
                                    METADATA_JSON, 90, 85);

        Map<String, byte[]> tiles = new LinkedHashMap<>();
        tiles.put("http://x/iiif/img/0,0,32,32/full/0/default.jpg", left);
        tiles.put("http://x/iiif/img/32,0,32,32/full/0/default.jpg", right);

        BufferedImage stitched = solid(64, 32, Color.WHITE);
        byte[] result = new UltraHdrAssembler(codec).assemble(stitched, tiles);

        assertThat(result).isNotNull();
        GainMapCodec.UhdrSplit split = codec.decode(result);
        assertThat(split.gainmapJpeg()).isNotEmpty();
        assertThat(split.metadataJson()).contains("alternateHdrHeadroom");
        BufferedImage gainImage = ImageIO.read(new java.io.ByteArrayInputStream(split.gainmapJpeg()));
        // Full gain map at native (2:1) resolution: 32x16.
        assertThat(gainImage.getWidth()).isEqualTo(32);
        assertThat(gainImage.getHeight()).isEqualTo(16);
        BufferedImage primary = ImageIO.read(new java.io.ByteArrayInputStream(split.primaryJpeg()));
        assertThat(primary.getWidth()).isEqualTo(64);
        assertThat(primary.getHeight()).isEqualTo(32);
    }

    @Test
    @DisplayName("no gain map in any tile yields null")
    void noGainMapYieldsNull() throws Exception {
        Map<String, byte[]> tiles = new LinkedHashMap<>();
        tiles.put("http://x/iiif/img/0,0,32,32/full/0/default.jpg", jpeg(32, 32, Color.RED));
        byte[] result = new UltraHdrAssembler(codec).assemble(solid(32, 32, Color.WHITE), tiles);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("null stitched primary is rejected")
    void nullPrimaryRejected() {
        assertThatThrownBy(() -> new UltraHdrAssembler(codec).assemble(null, Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("region parsing handles region-form tile URLs")
    void parseRegion() {
        UltraHdrAssembler.Region region = UltraHdrAssembler.parseRegion(
            "http://x/iiif/img/12,34,56,78/full/0/default.jpg");
        assertThat(region).isNotNull();
        assertThat(region).isEqualTo(new UltraHdrAssembler.Region(12, 34, 56, 78));
    }
}
