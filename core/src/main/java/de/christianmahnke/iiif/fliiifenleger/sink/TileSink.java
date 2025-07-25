package de.christianmahnke.iiif.fliiifenleger.sink;

import de.christianmahnke.iiif.fliiifenleger.ImageInfo;

import java.io.OutputStream;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;

/**
 * A sink for writing generated IIIF image tiles.
 */
public interface TileSink {
    /**
     * Saves a given image tile to the specified path.
     *
     * @param outputStream The destination stream for the tile.
     * @param image The image data to save.
     * @throws TileSinkException if an error occurs during saving.
     */
    void saveTile(OutputStream outputStream, BufferedImage image, Map<String, Object> metadata) throws TileSinkException;

    /**
     * @return The file extension for the format this sink writes (e.g., "jpg", "png").
     */
    String getFormatExtension();

    /**
     * @return The unique name for this sink implementation (e.g., "default", "jpeg").
     */
    String getName();

    /**
     * Sets configuration options for this tile sink.
     *
     * @param options A map of key-value pairs.
     */
    default void setOptions(Map<String, String> options) {}

}