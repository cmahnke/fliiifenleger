// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/iiif/fliiifenleger/ultrahdr/UltraHdrException.java
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmException;

/**
 * Thrown when an ultrahdr WASM function returns an error string, or the
 * module fails to load or execute.
 */
public class UltraHdrException extends WasmException {

    private static final long serialVersionUID = 1L;

    public UltraHdrException(String message) {
        super(message);
    }

    public UltraHdrException(String message, Throwable cause) {
        super(message, cause);
    }
}
