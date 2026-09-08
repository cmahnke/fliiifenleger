// src/main/java/de/christianmahnke/iiif/fliiifenleger/wasm/WasmException.java
package de.christianmahnke.iiif.fliiifenleger.wasm;

/**
 * Base exception for WASM module errors: a module function returned an error
 * string, or the runtime failed to load or execute the module.
 *
 * <p>Codec modules derive their own exceptions from this type (e.g.
 * {@code C2paException}, {@code UltraHdrException}) so callers can catch
 * either the codec-specific or the generic WASM error.
 */
public class WasmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WasmException(String message) {
        super(message);
    }

    public WasmException(String message, Throwable cause) {
        super(message, cause);
    }
}
