package de.christianmahnke.iiif.fliiifenleger;

public class TilerException extends Exception {
    public TilerException(String message) {
        super(message);
    }

    public TilerException(Exception cause) {
        super(cause);
    }

        public TilerException(String message, Exception cause) {
        super(message,cause);
    }
}