// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.cli;

import de.christianmahnke.jc2pa.TileSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("generate with parallel signing lanes")
class ParallelSigningTest {

    @Test
    @DisplayName("--sink-opt threads=4 signs every tile")
    void parallelGenerateSignsAllTiles(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("input.jpg");
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.GREEN);
            g.fillRect(0, 0, 128, 128);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "jpg", input.toFile());

        Path output = tempDir.resolve("iiif");
        int exit = new CommandLine(new Main.GenerateCommand()).execute(
            "--sink", "c2pa",
            "--sink-opt", "threads=4",
            "--sink-opt", "cert-name=parallel-test",
            "-o", output.toString(),
            input.toString());
        assertThat(exit).isZero();

        int validated = 0;
        try (TileSigner signer = new TileSigner((String) null);
             Stream<Path> tiles = Files.walk(output)) {
            for (Path tile : (Iterable<Path>) tiles.filter(p -> p.toString().endsWith(".jpg"))::iterator) {
                byte[] bytes = Files.readAllBytes(tile);
                assertThat(signer.activeLabel(bytes, "image/jpeg")).isNotBlank();
                validated++;
            }
        }
        assertThat(validated).isGreaterThanOrEqualTo(1);
    }
}
