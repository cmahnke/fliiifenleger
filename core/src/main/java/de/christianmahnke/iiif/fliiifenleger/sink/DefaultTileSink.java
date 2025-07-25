package de.christianmahnke.iiif.fliiifenleger.sink;

import com.google.auto.service.AutoService;
import lombok.NoArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

@AutoService(TileSink.class)
@NoArgsConstructor
public class DefaultTileSink extends AbstractTileSink {
    private static final Logger log = LoggerFactory.getLogger(DefaultTileSink.class);

    @Override
    public void saveTile(OutputStream outputStream, BufferedImage image, Map<String, Object> metadata) throws TileSinkException {
        BufferedImage imageToSave;
        // Handle transparency for formats that don't support it (like JPEG)
        if (image.getTransparency() != BufferedImage.TRANSLUCENT || "png".equalsIgnoreCase(this.format)) {
            imageToSave = image;
        } else {
            imageToSave = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = imageToSave.createGraphics();
            try {
                g.setColor(Color.WHITE); // Default background
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        if (metadata != null && !metadata.isEmpty()) {
            log.trace("Image metadata available: {}", metadata.keySet());
        }
        try {
            ImageIO.write(imageToSave, format, outputStream);
        } catch (IOException e) {
            throw new TileSinkException("Failed to write tile with format " + format, e);
        }
    }

    @Override
    public String getName() { return "default"; }
}