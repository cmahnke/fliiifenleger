// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.source;

import de.christianmahnke.iiif.fliiifenleger.TilerException;

public class ImageSourceException extends TilerException {
    public ImageSourceException(String message, Exception cause) {
        super(message, cause);
    }

    public ImageSourceException(String message) {
        super(message);
    }
}