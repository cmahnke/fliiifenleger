package de.christianmahnke.iiif.fliiifenleger.source;

import de.christianmahnke.iiif.fliiifenleger.TilerException;
import de.christianmahnke.iiif.fliiifenleger.source.ImageSourceException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;

public abstract class AbstractImageSource implements ImageSource {

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