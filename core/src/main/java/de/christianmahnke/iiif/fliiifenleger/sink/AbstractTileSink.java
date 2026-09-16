// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

package de.christianmahnke.iiif.fliiifenleger.sink;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import de.christianmahnke.iiif.fliiifenleger.OptionDescriptor;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.awt.image.BufferedImage;

public abstract class AbstractTileSink implements TileSink {

    protected String format = "jpg"; // Default format

    @Override
    public String getFormatExtension() {
        return format;
    }

    @Override
    public void setOptions(Map<String, String> options) {
        if (options != null) {
            this.format = options.getOrDefault("format", this.format);
        }
    }

    @Override
    public List<OptionDescriptor> getAvailableOptions() {
        return List.of(
                OptionDescriptor.optional("format",
                        "Tile image format written by this sink (e.g. jpg, png).",
                        "jpg"));
    }

    public void saveTile(OutputStream outputStream, BufferedImage image) throws TileSinkException {
        this.saveTile(outputStream, image, null);
    }
    @Deprecated
    public Path getBasePath(Path outputDir, ImageInfo imageInfo) {
        return outputDir.resolve(imageInfo.getImage().getUrl().getPath().substring(imageInfo.getImage().getUrl().getPath().lastIndexOf('/') + 1).replaceFirst("[.][^.]+$", ""));
    }
}