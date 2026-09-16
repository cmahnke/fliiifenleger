// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.source;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HDR read parity for the NightMonkeys (libjxl) implementation.
 *
 * <p>Unlike {@link JxlImageSourceTest} (pinned to JRE 22 and therefore
 * skipped on current toolchains), this runs wherever the native libjxl
 * is loadable — CI installs it, local macOS needs {@code
 * -Djava.library.path=/opt/homebrew/lib}.  Without the plugin the
 * tests skip instead of failing.
 */
@DisplayName("JXL HDR (NightMonkeys)")
class JxlHdrReadTest {

    private static void assumeJxlReader() {
        Assumptions.assumeTrue(
            ImageIO.getImageReadersByFormatName("jxl").hasNext(),
            "NightMonkeys imageio-jxl plugin not available (native libjxl missing?)");
    }

    @Test
    @DisplayName("HDR input decodes without error")
    void hdrInputDecodes() throws Exception {
        assumeJxlReader();
        File hdrFile = new File("src/test/resources/images/hdr-pq.jxl");
        Assumptions.assumeTrue(hdrFile.exists(), "HDR fixture must exist");
        JxlImageSource source = new JxlImageSource();
        source.load(hdrFile.toURI().toURL());
        BufferedImage image = source.getImage();
        assertNotNull(image, "HDR image should decode");
        assertEquals(256, source.getWidth(), "HDR width should match");
        assertEquals(256, source.getHeight(), "HDR height should match");
    }
}
