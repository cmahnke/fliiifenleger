// src/main/java/de/christianmahnke/jc2pa/C2paReader.java
package de.christianmahnke.jc2pa;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmMemory;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;

/**
 * High-level wrapper around the c2pa WASM reader functions.
 *
 * <p>Manages the lifetime of the underlying WASM reader handle.  Must be
 * closed when no longer needed to release the WASM-side allocation.
 *
 * <pre>{@code
 * try (C2paReader reader = C2paReader.fromBytes(wasm, "image/jpeg", jpegBytes)) {
 *     System.out.println(reader.json());
 *     System.out.println(reader.activeLabel());
 * }
 * }</pre>
 *
 * <p>Mirrors the original {@code WasmReader} struct and its
 * {@code #[wasm_bindgen]} impl block.
 */
public class C2paReader implements Closeable {

    private final C2paWasm   wasm;
    private final WasmMemory mem;

    /** WASM-side opaque handle (i32) returned by reader_from_bytes et al. */
    private int handle;

    private boolean closed = false;

    // ── Construction ──────────────────────────────────────────────────────────

    private C2paReader(C2paWasm wasm, int handle) {
        this.wasm   = wasm;
        this.mem    = wasm.memory();
        this.handle = handle;
    }

    /**
     * Create a reader from an asset's raw bytes.
     *
     * <p>Mirrors {@code WasmReader::from_blob} (sync variant).
     *
     * @param wasm   Initialised {@link C2paWasm} instance.
     * @param format MIME type or file extension, e.g. {@code "image/jpeg"}.
     * @param data   Raw asset bytes.
     * @return A new {@link C2paReader}; caller must close it.
     * @throws C2paException if the WASM function returns an error.
     */
    public static C2paReader fromBytes(C2paWasm wasm, String format, byte[] data)
            throws C2paException {
        WasmMemory mem = wasm.memory();

        int formatPtr  = mem.allocString(format);
        int formatLen  = format.getBytes(StandardCharsets.UTF_8).length;
        int dataPtr    = mem.allocBytes(data);
        int dataLen    = data.length;
        int errPtrSlot = mem.allocPtrSlot();
        int errLenSlot = mem.allocU32Slot();

        int handle;
        try {
            handle = wasm.readerFromBytes(
                formatPtr, formatLen,
                dataPtr,   dataLen,
                errPtrSlot, errLenSlot);
        } finally {
            // Input buffers are only borrowed for the call duration.
            wasm.wasmFree(formatPtr, formatLen);
            wasm.wasmFree(dataPtr,   dataLen);
        }

        checkError(wasm, mem, handle, errPtrSlot, errLenSlot);
        freeSlots(wasm, errPtrSlot, errLenSlot);
        return new C2paReader(wasm, handle);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Return the active manifest label, or {@code null} if none.
     *
     * <p>Mirrors {@code WasmReader::active_label()}.
     *
     * @return Label string or {@code null}.
     * @throws IllegalStateException if this reader has been closed.
     */
    public String activeLabel() {
        ensureOpen();

        int outLenSlot = mem.allocU32Slot();
        int resultPtr;
        try {
            resultPtr = wasm.readerActiveLabel(handle, outLenSlot);
        } catch (Exception e) {
            wasm.wasmFree(outLenSlot, 4);
            throw e;
        }

        if (resultPtr == 0) {
            wasm.wasmFree(outLenSlot, 4);
            return null;
        }

        int    len    = mem.readU32(outLenSlot);
        String result = mem.readString(resultPtr, len);
        wasm.wasmFree(resultPtr,  len);
        wasm.wasmFree(outLenSlot, 4);
        return result;
    }

    /**
     * Return the manifest store as a JSON string.
     *
     * <p>Mirrors {@code WasmReader::json()}.
     *
     * @return JSON string.
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this reader has been closed.
     */
    public String json() throws C2paException {
        ensureOpen();
        return callStringFn(wasm::readerJson);
    }

    /**
     * Return the active manifest as a JSON string.
     *
     * <p>Mirrors {@code WasmReader::active_manifest()}.
     *
     * @return JSON string.
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this reader has been closed.
     */
    public String activeManifestJson() throws C2paException {
        ensureOpen();
        return callStringFn(wasm::readerActiveManifestJson);
    }

    /**
     * Return the validation results of the manifest store as a JSON string.
     *
     * @return JSON string, or {@code null} when the store has no validation
     *         results.
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this reader has been closed.
     */
    public String validationResultsJson() throws C2paException {
        ensureOpen();
        int outLenSlot = mem.allocU32Slot();
        int errPtrSlot = mem.allocPtrSlot();
        int errLenSlot = mem.allocU32Slot();

        int resultPtr = wasm.readerValidationResults(handle, outLenSlot, errPtrSlot, errLenSlot);

        C2paReader.checkError(wasm, mem, resultPtr, errPtrSlot, errLenSlot);
        C2paReader.freeSlots(wasm, errPtrSlot, errLenSlot);

        if (resultPtr == 0) {
            // No validation results — not an error.
            wasm.wasmFree(outLenSlot, 4);
            return null;
        }

        int    len    = mem.readU32(outLenSlot);
        String result = mem.readString(resultPtr, len);
        wasm.wasmFree(resultPtr,  len);
        wasm.wasmFree(outLenSlot, 4);
        return result;
    }

    /**
     * Return the validation state of the manifest store as a string:
     * {@code "Valid"}, {@code "Invalid"}, or {@code "Trusted"}.
     *
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this reader has been closed.
     */
    public String validationState() throws C2paException {
        ensureOpen();
        int outLenSlot = mem.allocU32Slot();
        int errPtrSlot = mem.allocPtrSlot();
        int errLenSlot = mem.allocU32Slot();

        int resultPtr = wasm.readerValidationState(handle, outLenSlot, errPtrSlot, errLenSlot);
        C2paReader.checkError(wasm, mem, resultPtr, errPtrSlot, errLenSlot);
        C2paReader.freeSlots(wasm, errPtrSlot, errLenSlot);

        int    len    = mem.readU32(outLenSlot);
        String result = mem.readString(resultPtr, len);
        wasm.wasmFree(resultPtr,  len);
        wasm.wasmFree(outLenSlot, 4);
        return result;
    }

    // ── Closeable ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        if (!closed && handle != 0) {
            wasm.readerFree(handle);
            handle = 0;
            closed = true;
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("C2paReader has been closed");
        }
    }

    /**
     * Functional interface for WASM string-returning functions that share
     * the signature {@code (handle, outLenSlot, errPtr, errLen) -> resultPtr}.
     */
    @FunctionalInterface
    private interface StringWasmFn {
        int call(int handle, int outLenSlot, int errPtr, int errLen);
    }

    /**
     * Call a {@link StringWasmFn}, read the returned UTF-8 string, free all
     * WASM memory, and throw {@link C2paException} on error.
     */
    private String callStringFn(StringWasmFn fn) throws C2paException {
        int outLenSlot = mem.allocU32Slot();
        int errPtrSlot = mem.allocPtrSlot();
        int errLenSlot = mem.allocU32Slot();

        int resultPtr = fn.call(handle, outLenSlot, errPtrSlot, errLenSlot);

        checkError(wasm, mem, resultPtr, errPtrSlot, errLenSlot);
        freeSlots(wasm, errPtrSlot, errLenSlot);

        int    len    = mem.readU32(outLenSlot);
        String result = mem.readString(resultPtr, len);
        wasm.wasmFree(resultPtr,  len);
        wasm.wasmFree(outLenSlot, 4);
        return result;
    }

    // ── Package-private static helpers (shared with C2paBuilder, C2paSigner) ─

    /**
     * If {@code resultHandle} is {@code 0} (the WASM failure sentinel), read
     * the error string from the out-parameter slots and throw
     * {@link C2paException}.  On success this method is a no-op.
     *
     * <p>The error buffer is freed inside this method on failure.  The
     * out-parameter slots themselves are NOT freed here; call
     * {@link #freeSlots} after this returns successfully.
     *
     * @param wasm         The WASM module (for {@link C2paWasm#wasmFree}).
     * @param mem          Linear memory helper (for reads).
     * @param resultHandle The value returned by the WASM call ({@code 0} = failure).
     * @param errPtrSlot   WASM address of the {@code *mut *mut u8} slot.
     * @param errLenSlot   WASM address of the {@code *mut u32} length slot.
     * @throws C2paException if {@code resultHandle == 0}.
     */
    static void checkError(
            C2paWasm wasm, WasmMemory mem,
            int resultHandle,
            int errPtrSlot, int errLenSlot) throws C2paException {
        if (resultHandle == 0) {
            int errBufPtr = mem.readPtr(errPtrSlot);
            int errBufLen = mem.readU32(errLenSlot);

            String message = "unknown c2pa error";
            if (errBufPtr != 0 && errBufLen > 0) {
                message = mem.readString(errBufPtr, errBufLen);
                wasm.wasmFree(errBufPtr, errBufLen);
            }
            freeSlots(wasm, errPtrSlot, errLenSlot);
            throw new C2paException(message);
        }
    }

    /**
     * Free the two 4-byte out-parameter slots allocated for error reporting.
     *
     * <p>Always call this after {@link #checkError} when the call succeeded
     * (i.e. when {@link #checkError} did not throw).
     *
     * @param wasm         The WASM module (for {@link C2paWasm#wasmFree}).
     * @param errPtrSlot   WASM address of the pointer slot.
     * @param errLenSlot   WASM address of the length slot.
     */
    static void freeSlots(C2paWasm wasm, int errPtrSlot, int errLenSlot) {
        wasm.wasmFree(errPtrSlot, 4);
        wasm.wasmFree(errLenSlot, 4);
    }
}