package de.christianmahnke.iiif.fliiifenleger.sink;

import de.christianmahnke.iiif.fliiifenleger.TilerException;

public class TileSinkException extends TilerException {
    public TileSinkException(String message) {
        super(message);
    }

    public TileSinkException(String message, Exception cause) {
        super(message, cause);
    }
}