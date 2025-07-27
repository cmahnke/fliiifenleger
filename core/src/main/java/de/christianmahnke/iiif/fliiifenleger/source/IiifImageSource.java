/**
 * Fliiifenleger
 * Copyright (C) 2025  Christian Mahnke
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.christianmahnke.iiif.fliiifenleger.source;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.ImageInfo;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.io.Reader;

@AutoService(ImageSource.class)
@NoArgsConstructor
public class IiifImageSource extends AbstractImageSource implements ImageSource {
    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final String NAME = "iiif";

    private URI imageBaseUri;
    private int width;
    private int height;
    private JsonObject infoJson;
    private int apiLevel = -1; // -1: unknown, 0: level 0, etc.
    private ImageInfo.IIIFVersion apiVersion;

    @Override
    public void load(URL url)throws ImageSourceException{
        this.url = url;
        loadImage();
    }

    private void loadImage()throws ImageSourceException  {
try {
            loadInfoJson();
        } catch (IOException | URISyntaxException | ImageSourceException e) {
            // Wrap in a runtime exception because the interface doesn't allow throwing checked exceptions here.
            throw new ImageSourceException("Failed to load info.json from URL: " + url, e);
        }
    }

    private void loadInfoJson() throws IOException, URISyntaxException, ImageSourceException {
        log.debug("Fetching info.json from: {}", url);
        try (InputStream is = getInputStream(url);
            Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
            JsonReader jsonReader = Json.createReader(reader)) {
            this.infoJson = jsonReader.readObject();

            // Handle v3 ("id") and v2 ("@id") identifier property
            String imageIdStr;
            if (this.infoJson.containsKey("id")) {
                this.apiVersion = ImageInfo.IIIFVersion.V3;
                imageIdStr = this.infoJson.getString("id");
            } else {
                this.apiVersion = ImageInfo.IIIFVersion.V2;
                imageIdStr = this.infoJson.getString("@id");
            }
            this.imageBaseUri = new URI(imageIdStr);
            this.width = this.infoJson.getInt("width");
            this.height = this.infoJson.getInt("height");

            // Check for compliance level to handle Level 0 servers
            if (this.infoJson.containsKey("profile")) {
                // The profile can be a string or an array. We just need to find the level.
                String profileStr = this.infoJson.get("profile").toString();
                if (profileStr.contains("level0")) {
                    this.apiLevel = 0;
                    log.info("Detected IIIF API Level 0 compliance for {}. Cropping will be done in-memory.", this.url);
                } else if (profileStr.contains("level1")) {
                    this.apiLevel = 1;
                } else if (profileStr.contains("level2")) {
                    this.apiLevel = 2;
                }
            } else {
                log.info("No profaile detected in {}.", this.url);
            }
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getWidth() {
        if (infoJson == null) {
            throw new IllegalStateException("IIIF Image Source not initialized. Call setUrl() first.");
        }
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }
    private void ensureInitialized() {
        if (this.imageBaseUri == null) throw new IllegalStateException("IIIF Image Source not initialized. Call setUrl() first.");
    }

    @Override
    public BufferedImage crop(int x, int y, int width, int height, double scale) throws ImageSourceException {
        if (this.imageBaseUri == null) {
            throw new IllegalStateException("IIIF Image Source not initialized. info.json might be missing or corrupt.");
        }

        // For Level 0, we must download the full image and crop/scale locally
        if (this.apiLevel == 0) {
            return cropInMemory(x, y, width, height, scale);
        }

        // IIIF region is x,y,w,h
        String region = String.format("%d,%d,%d,%d", x, y, width, height);

        // IIIF size is w,h or pct:n
        String size;
        if (scale == 1.0) {
            /*
            if (apiVersion == ImageInfo.IIIFVersion.V3) {
                size = "max";
            } else {
                size = "full";
            }
            */
            size = "max";
        } else {
            int newWidth = (int) Math.ceil(width / scale);
            int newHeight = (int) Math.ceil(height / scale);
            size = String.format("%d,%d", newWidth, newHeight);
        }

        // Construct the IIIF URL: {scheme}://{server}{/prefix}/{identifier}/{region}/{size}/{rotation}/{quality}.{format}
        String iiifUrlString = String.format("%s/%s/%s/0/default.jpg", this.imageBaseUri.toString(), region, size);

        try {
            URL imageUrl = new URI(iiifUrlString).toURL();
            log.debug("Fetching IIIF image region: {}", imageUrl);
            BufferedImage image = ImageIO.read(getInputStream(imageUrl));
            if (image == null) {
                throw new ImageSourceException("Failed to read image from IIIF URL (is it a valid image?): " + imageUrl);
            }
            return image;
        } catch (MalformedURLException | URISyntaxException e) {
            throw new ImageSourceException("Created an invalid IIIF URL: " + iiifUrlString, e);
        } catch (IOException e) {
            throw new ImageSourceException("Could not read image from IIIF URL: " + iiifUrlString, e);
        }
    }

    private BufferedImage cropInMemory(int x, int y, int width, int height, double scale) throws ImageSourceException {
        log.warn("Performing in-memory crop for Level 0 IIIF source. This may be slow and memory-intensive.");
        // Level 0 only guarantees /full/full/0/default.jpg
        String fullImageUrlString = String.format("%s/full/full/0/default.jpg", this.imageBaseUri.toString());
        try {
            URL imageUrl = new URI(fullImageUrlString).toURL();
            log.debug("Fetching full IIIF image for in-memory crop: {}", imageUrl);
            BufferedImage fullImage = ImageIO.read(getInputStream(imageUrl));
            if (fullImage == null) {
                throw new ImageSourceException("Failed to read full image from IIIF URL: " + imageUrl);
            }

            BufferedImage cropped = fullImage.getSubimage(x, y, width, height);

            if (scale == 1.0) {
                return cropped;
            }

            int newWidth = (int) Math.ceil(width / scale);
            int newHeight = (int) Math.ceil(height / scale);
            BufferedImage scaled = new BufferedImage(newWidth, newHeight, cropped.getType());
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(cropped, 0, 0, newWidth, newHeight, null);
            g.dispose();
            return scaled;
        } catch (Exception e) {
            throw new ImageSourceException("Failed to perform in-memory crop for IIIF source: " + this.url, e);
        }
    }

    @Override
    public Map<String, Object> getMetadata() {
        // We can expose the entire info.json as metadata.
        // For simplicity, we'll just return a few key fields.
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("iiif_source_id", imageBaseUri.toString());
        metadata.put("iiif_profile", infoJson.get("profile"));
        return metadata;
    }
}