// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
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