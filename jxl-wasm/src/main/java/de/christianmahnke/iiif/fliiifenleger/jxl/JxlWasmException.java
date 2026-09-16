// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/iiif/fliiifenleger/jxl/JxlWasmException.java
package de.christianmahnke.iiif.fliiifenleger.jxl;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmException;

/**
 * Thrown when a jxl-wasm function returns an error string, or the module
 * fails to load or execute.
 */
public class JxlWasmException extends WasmException {

    private static final long serialVersionUID = 1L;

    public JxlWasmException(String message) {
        super(message);
    }

    public JxlWasmException(String message, Throwable cause) {
        super(message, cause);
    }
}
