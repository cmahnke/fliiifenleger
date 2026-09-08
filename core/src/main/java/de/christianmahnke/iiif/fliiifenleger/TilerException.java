// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
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