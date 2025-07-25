package de.christianmahnke.iiif.fliiifenleger.source;

import de.christianmahnke.iiif.fliiifenleger.TilerException;

public class ImageSourceException extends TilerException {
    public ImageSourceException(String message, Exception cause) {
        super(message);
    }

    public ImageSourceException(String message) {
        super(message);
    }
}