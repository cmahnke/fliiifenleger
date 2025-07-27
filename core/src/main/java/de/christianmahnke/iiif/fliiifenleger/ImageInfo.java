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

package de.christianmahnke.iiif.fliiifenleger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSource;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ImageInfo {
    @Getter
    private final ImageSource image;
    @Getter
    private final int tileWidth;
    @Getter
    private final int tileHeight;
    @Getter
    private final List<Integer> scaleFactors;
    @Getter
    private final List<Size> sizes;
    private final String identifier;
    private final IIIFVersion version;

    public ImageInfo(ImageSource image, int tileWidth, int tileHeight, int zoomLevels, String identifier, IIIFVersion version) {
        this.image = image;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.scaleFactors = calculateScaleFactors(zoomLevels);
        this.sizes = calculateSizes(image.getWidth(), image.getHeight());
        this.identifier = identifier;
        this.version = version;
    }

    private List<Integer> calculateScaleFactors(int zoomLevels) {
        List<Integer> factors = new ArrayList<>();
        for (int i = 0; i < zoomLevels; i++) {
            factors.add((int) Math.pow(2, i));
        }
        return Collections.unmodifiableList(factors);
    }

    /**
     * Calculates the optimal number of zoom levels based on image and tile dimensions.
     * The number of levels is determined by how many times the longest side of the image
     * can be halved until it is smaller than or equal to the tile size.
     *
     * @param width The width of the image.
     * @param height The height of the image.
     * @param tileSize The size of one side of a square tile.
     * @return The calculated number of zoom levels.
     */
    public static int calculateZoomLevels(int width, int height, int tileSize) {
        double maxDim = Math.max(width, height);
        return (int) Math.ceil(Math.log(maxDim / tileSize) / Math.log(2)) + 1;
    }

    private List<Size> calculateSizes(int width, int height) {
        List<Size> sizeList = new ArrayList<>();
        int currentWidth = width;
        int currentHeight = height;

        while (currentWidth > tileWidth || currentHeight > tileHeight) {
            if (currentWidth > tileWidth) {
                sizeList.add(new Size(currentWidth, currentHeight));
            }
            currentWidth /= 2;
            currentHeight /= 2;
        }
        sizeList.add(new Size(currentWidth, currentHeight));
        return Collections.unmodifiableList(sizeList);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        if (version == IIIFVersion.V3) {
            json.put("@context", "http://iiif.io/api/image/3/context.json");
            json.put("id", identifier + image.getUrl().getPath().substring(image.getUrl().getPath().lastIndexOf('/') + 1).replaceFirst("[.][^.]+$", ""));
            json.put("type", "ImageService3");
            json.put("protocol", "http://iiif.io/api/image");
            json.put("profile", "level2");
            json.put("width", image.getWidth());
            json.put("height", image.getHeight());
            json.put("maxWidth", image.getWidth());
            json.put("maxHeight", image.getHeight());

            List<Tile> tiles = new ArrayList<>();
            Tile tile = new Tile();
            tile.width = getTileWidth();
            tile.height = getTileHeight();
            tile.scaleFactors = getScaleFactors();
            tiles.add(tile);
            json.put("tiles", tiles);

            json.put("sizes", getSizes().stream()
                    .map(s -> Map.of("width", s.width(), "height", s.height()))
                    .collect(Collectors.toList()));

        } else { // V2
            json.put("@context", "http://iiif.io/api/image/2/context.json");
            json.put("@id", identifier + image.getUrl().getPath().substring(image.getUrl().getPath().lastIndexOf('/') + 1).replaceFirst("[.][^.]+$", ""));
            json.put("protocol", "http://iiif.io/api/image");
            json.put("profile", List.of("http://iiif.io/api/image/2/level2.json"));
            json.put("width", image.getWidth());
            json.put("height", image.getHeight());

            List<Tile> tiles = new ArrayList<>();
            Tile tile = new Tile();
            tile.width = getTileWidth();
            // V2 doesn't have tile height in the same way
            tile.scaleFactors = getScaleFactors();
            tiles.add(tile);
            json.put("tiles", tiles);

            json.put("sizes", getSizes().stream()
                    .map(s -> Map.of("width", s.width(), "height", s.height()))
                    .collect(Collectors.toList()));
        }
        return json;
    }

    public record Size(int width, int height) {

        @Override
            public String toString() {
                return "Size{" +
                        "width=" + width +
                        ", height=" + height +
                        '}';
            }
        }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class Tile {
        @JsonProperty
        public int width;
        @JsonProperty
        public Integer height;
        @JsonProperty
        public List<Integer> scaleFactors;
    }

    /**
     * Enum representing supported IIIF Image API versions.
     */
    public enum IIIFVersion {
        V2("2", "2.1.1"),
        V3("3", "3.0.0");

        private final String shortName;
        private final String exactVersion;

        IIIFVersion(String shortName, String exactVersion) {
            this.shortName = shortName;
            this.exactVersion = exactVersion;
        }

        public String getShortName() {
            return shortName;
        }

        public String getExactVersion() {
            return exactVersion;
        }
    }
}
