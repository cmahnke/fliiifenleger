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

import de.christianmahnke.iiif.fliiifenleger.TilerException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;

import java.awt.image.BufferedImage;

public abstract class AbstractImageSource implements ImageSource {
protected URL url = null;
@Override
    public BufferedImage getImage() throws ImageSourceException{
            return this.crop(0, 0, this.getWidth(), this.getHeight(), 1.0);

        }

    @Override
    public URL getUrl() {
        return this.url;
    }

    @Override
    public void load(URL url)throws ImageSourceException{
        this.url = url;
    }

    @Override
    public int getWidth() {
            throw new IllegalStateException("ImageSource not initilized.");

    }

    @Override
    public int getHeight() {
            throw new IllegalStateException("ImageSource not initilized.");
    }

    /**
     * Returns an InputStream for a given URL.
     * @param url The URL to open a stream to.
     * @return An InputStream for the URL.
     * @throws TilerException if the stream cannot be opened.
     */
    public static InputStream getInputStream(URL url) throws ImageSourceException {
        try {
            return url.openStream();
        } catch (IOException e) {
            throw new ImageSourceException("Could not open stream for URL: " + url, e);
        }
    }
}