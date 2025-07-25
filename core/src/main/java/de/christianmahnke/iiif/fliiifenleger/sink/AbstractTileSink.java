package de.christianmahnke.iiif.fliiifenleger.sink;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;

import java.io.OutputStream;
import java.nio.file.Path;
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

    public void saveTile(OutputStream outputStream, BufferedImage image) throws TileSinkException {
        this.saveTile(outputStream, image, null);
    }
    @Deprecated
    public Path getBasePath(Path outputDir, ImageInfo imageInfo) {
        return outputDir.resolve(imageInfo.getImage().getUrl().getPath().substring(imageInfo.getImage().getUrl().getPath().lastIndexOf('/') + 1).replaceFirst("[.][^.]+$", ""));
    }
}