// src/main/java/de/christianmahnke/jc2pa/C2paException.java
package de.christianmahnke.jc2pa;

/**
 * Thrown when a c2pa WASM function returns an error string.
 *
 * <p>The message contains the UTF-8 error string returned by the Rust
 * {@code WasmError} type, which wraps {@code c2pa::Error},
 * {@code serde_json::Error}, or a boxed {@code std::error::Error}.
 */
public class C2paException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public C2paException(String message) {
        super(message);
    }

    public C2paException(String message, Throwable cause) {
        super(message, cause);
    }
}