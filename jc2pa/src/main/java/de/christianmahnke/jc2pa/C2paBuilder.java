// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/jc2pa/C2paBuilder.java
package de.christianmahnke.jc2pa;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmMemory;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;

/**
 * High-level wrapper around the c2pa WASM builder functions.
 *
 * <p>Manages the lifetime of the underlying WASM builder handle.
 *
 * <p>Signing happens <em>inside</em> the WASM module.  Two paths exist:
 * <ol>
 *   <li>{@link #signWithKeys} — the host supplies a PEM certificate chain
 *       and a PEM private key; the signature is computed by c2pa's
 *       {@code create_signer::from_keys}.</li>
 *   <li>{@link #signEphemeral} — signs with an ephemeral self-signed
 *       certificate chain; for tests and demos only (the result will not
 *       validate against any trust list).</li>
 * </ol>
 *
 * <p>After a successful sign the builder is consumed and must not be used
 * again; the close() call then becomes a no-op.
 *
 * <pre>{@code
 * try (C2paBuilder builder = new C2paBuilder(wasm, manifestJson)) {
 *     byte[] signed = builder.signEphemeral("image/jpeg", tileBytes, "fliiifenleger");
 * }
 * }</pre>
 */
public class C2paBuilder implements Closeable {

    private final C2paWasm   wasm;
    private final WasmMemory mem;

    /** WASM-side opaque builder handle. */
    private int handle;

    private boolean closed = false;

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Create a new builder from a manifest definition JSON string.
     *
     * <p>Mirrors {@code Builder::from_context(ctx).with_definition(json)}
     * in {@code lib.rs}.
     *
     * @param wasm         Initialised {@link C2paWasm} instance.
     * @param manifestJson Manifest definition in c2pa JSON format.
     * @throws C2paException if the WASM function returns an error.
     */
    public C2paBuilder(C2paWasm wasm, String manifestJson) throws C2paException {
        this.wasm = wasm;
        this.mem  = wasm.memory();

        int jsonPtr    = mem.allocString(manifestJson);
        int jsonLen    = manifestJson.getBytes(StandardCharsets.UTF_8).length;
        int errPtrSlot = mem.allocPtrSlot();
        int errLenSlot = mem.allocU32Slot();

        int h;
        try {
            h = wasm.builderFromJson(jsonPtr, jsonLen, errPtrSlot, errLenSlot);
        } finally {
            wasm.wasmFree(jsonPtr, jsonLen);
        }

        C2paReader.checkError(wasm, mem, h, errPtrSlot, errLenSlot);
        C2paReader.freeSlots(wasm, errPtrSlot, errLenSlot);
        this.handle = h;
    }

    /**
     * Set the builder intent.
     *
     * @param intent One of {@code "edit"}, {@code "update"}, or
     *               {@code "create:<source type>"} where the source type is
     *               a Digital Source Type alias (e.g.
     *               {@code "create:digitalCapture"}) or IPTC URI.
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this builder has been closed.
     */
    public void setIntent(String intent) throws C2paException {
        ensureOpen();

        int intentPtr   = mem.allocString(intent);
        int intentLen   = intent.getBytes(StandardCharsets.UTF_8).length;
        int errPtrSlot  = mem.allocPtrSlot();
        int errLenSlot  = mem.allocU32Slot();

        int result;
        try {
            result = wasm.builderSetIntent(handle, intentPtr, intentLen,
                                           errPtrSlot, errLenSlot);
        } finally {
            wasm.wasmFree(intentPtr, intentLen);
        }

        checkResult(wasm, mem, result, errPtrSlot, errLenSlot);
    }

    /**
     * Add an ingredient to the manifest from an asset's raw bytes.
     *
     * @param ingredientJson Ingredient definition JSON, e.g.
     *                       {@code {"title": "...", "relationship": "componentOf"}}.
     * @param format         MIME type or file extension of the ingredient
     *                       asset.
     * @param data           Raw ingredient asset bytes.
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this builder has been closed.
     */
    public void addIngredient(String ingredientJson, String format, byte[] data)
            throws C2paException {
        ensureOpen();

        int jsonPtr   = mem.allocString(ingredientJson);
        int jsonLen   = ingredientJson.getBytes(StandardCharsets.UTF_8).length;
        int formatPtr = mem.allocString(format);
        int formatLen = format.getBytes(StandardCharsets.UTF_8).length;
        int dataPtr   = mem.allocBytes(data);
        int dataLen   = data.length;
        int errPtrSlot = mem.allocPtrSlot();
        int errLenSlot = mem.allocU32Slot();

        int result;
        try {
            result = wasm.builderAddIngredient(handle, jsonPtr, jsonLen,
                                               formatPtr, formatLen,
                                               dataPtr, dataLen,
                                               errPtrSlot, errLenSlot);
        } finally {
            wasm.wasmFree(jsonPtr,   jsonLen);
            wasm.wasmFree(formatPtr, formatLen);
            wasm.wasmFree(dataPtr,   dataLen);
        }

        checkResult(wasm, mem, result, errPtrSlot, errLenSlot);
    }

    /**
     * Error check for the {@code 1/0} lifecycle exports (in contrast to the
     * {@code 0 = failure} handle convention used elsewhere).
     */
    private static void checkResult(
            C2paWasm wasm, WasmMemory mem,
            int result, int errPtrSlot, int errLenSlot) throws C2paException {
        if (result == 0) {
            int errBufPtr = mem.readPtr(errPtrSlot);
            int errBufLen = mem.readU32(errLenSlot);
            String message = "unknown c2pa error";
            if (errBufPtr != 0 && errBufLen > 0) {
                message = mem.readString(errBufPtr, errBufLen);
                wasm.wasmFree(errBufPtr, errBufLen);
            }
            C2paReader.freeSlots(wasm, errPtrSlot, errLenSlot);
            throw new C2paException(message);
        }
        C2paReader.freeSlots(wasm, errPtrSlot, errLenSlot);
    }

    // ── Public API (signing) ──────────────────────────────────────────────────
    /**
     * Sign an asset in memory using a PEM certificate chain and PEM private
     * key.  The signature is computed inside the WASM module.
     *
     * @param format     MIME type or file extension of the asset.
     * @param assetBytes Raw asset bytes to embed the manifest in.
     * @param certPem    PEM-encoded certificate chain bytes (must not be
     *                   {@code null}; use {@link #signEphemeral} to sign
     *                   without certificates).
     * @param keyPem     PEM-encoded private key bytes (must not be {@code null}).
     * @param alg        Signing algorithm string, e.g. {@code "ps256"},
     *                   {@code "es256"}, {@code "ed25519"}.
     * @param tsaUrl     Optional timestamp authority URL; {@code null} or
     *                   empty for no timestamping.
     * @return Signed asset bytes.
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this builder has been closed.
     */
    public byte[] signWithKeys(
            String format,
            byte[] assetBytes,
            byte[] certPem,
            byte[] keyPem,
            String alg,
            String tsaUrl) throws C2paException {
        ensureOpen();

        byte[] certs = certPem == null ? new byte[0] : certPem;
        byte[] keys  = keyPem  == null ? new byte[0] : keyPem;
        String tsa   = tsaUrl  == null ? ""         : tsaUrl;

        // ── Allocate input buffers ────────────────────────────────────────────
        int formatPtr = mem.allocString(format);
        int formatLen = format.getBytes(StandardCharsets.UTF_8).length;
        int assetPtr  = mem.allocBytes(assetBytes);
        int assetLen  = assetBytes.length;
        int certPtr   = mem.allocBytes(certs);
        int certLen   = certs.length;
        int keyPtr    = mem.allocBytes(keys);
        int keyLen    = keys.length;
        int algPtr    = mem.allocString(alg);
        int algLen    = alg.getBytes(StandardCharsets.UTF_8).length;
        int tsaPtr    = mem.allocString(tsa);
        int tsaLen    = tsa.getBytes(StandardCharsets.UTF_8).length;

        // ── Allocate out-parameter slots ──────────────────────────────────────
        int outLenSlot = mem.allocU32Slot();
        int errPtrSlot = mem.allocPtrSlot();
        int errLenSlot = mem.allocU32Slot();

        int resultPtr;
        try {
            resultPtr = wasm.builderSignWithKeys(
                handle,
                formatPtr, formatLen,
                assetPtr,  assetLen,
                certPtr,   certLen,
                keyPtr,    keyLen,
                algPtr,    algLen,
                tsaPtr,    tsaLen,
                outLenSlot,
                errPtrSlot, errLenSlot);
        } finally {
            wasm.wasmFree(formatPtr, formatLen);
            wasm.wasmFree(assetPtr,  assetLen);
            wasm.wasmFree(certPtr,   certLen);
            wasm.wasmFree(keyPtr,    keyLen);
            wasm.wasmFree(algPtr,    algLen);
            wasm.wasmFree(tsaPtr,    tsaLen);
        }

        // The builder is consumed on success — mirror that on the Java side
        // regardless of outcome so a failed sign cannot be retried on a
        // half-updated handle.
        handle = 0;
        closed = true;

        C2paReader.checkError(wasm, mem, resultPtr, errPtrSlot, errLenSlot);
        C2paReader.freeSlots(wasm, errPtrSlot, errLenSlot);

        int    len    = mem.readU32(outLenSlot);
        byte[] result = mem.readBytes(resultPtr, len);
        wasm.wasmFree(resultPtr,  len);
        wasm.wasmFree(outLenSlot, 4);
        return result;
    }

    /**
     * Sign an asset in memory using an ephemeral self-signed certificate
     * chain.  Intended for tests and demos only — the produced manifest will
     * not validate against any trust list.
     *
     * @param format     MIME type or file extension of the asset.
     * @param assetBytes Raw asset bytes to embed the manifest in.
     * @param certName   Common name for the ephemeral end-entity certificate.
     * @return Signed asset bytes.
     * @throws C2paException         on WASM error.
     * @throws IllegalStateException if this builder has been closed.
     */
    public byte[] signEphemeral(
            String format,
            byte[] assetBytes,
            String certName) throws C2paException {
        ensureOpen();

        // ── Allocate input buffers ────────────────────────────────────────────
        int formatPtr   = mem.allocString(format);
        int formatLen   = format.getBytes(StandardCharsets.UTF_8).length;
        int assetPtr    = mem.allocBytes(assetBytes);
        int assetLen    = assetBytes.length;
        int certNamePtr = mem.allocString(certName);
        int certNameLen = certName.getBytes(StandardCharsets.UTF_8).length;

        // ── Allocate out-parameter slots ──────────────────────────────────────
        int outLenSlot = mem.allocU32Slot();
        int errPtrSlot = mem.allocPtrSlot();
        int errLenSlot = mem.allocU32Slot();

        int resultPtr;
        try {
            resultPtr = wasm.builderSignEphemeral(
                handle,
                formatPtr,   formatLen,
                assetPtr,    assetLen,
                certNamePtr, certNameLen,
                outLenSlot,
                errPtrSlot,  errLenSlot);
        } finally {
            wasm.wasmFree(formatPtr,   formatLen);
            wasm.wasmFree(assetPtr,    assetLen);
            wasm.wasmFree(certNamePtr, certNameLen);
        }

        // The builder is consumed on success — mirror that on the Java side
        // regardless of outcome (see signWithKeys).
        handle = 0;
        closed = true;

        C2paReader.checkError(wasm, mem, resultPtr, errPtrSlot, errLenSlot);
        C2paReader.freeSlots(wasm, errPtrSlot, errLenSlot);

        int    len    = mem.readU32(outLenSlot);
        byte[] result = mem.readBytes(resultPtr, len);
        wasm.wasmFree(resultPtr,  len);
        wasm.wasmFree(outLenSlot, 4);
        return result;
    }

    // ── Closeable ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        if (!closed && handle != 0) {
            wasm.builderFree(handle);
            handle = 0;
            closed = true;
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("C2paBuilder has been closed");
        }
    }
}
