// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/main/java/de/christianmahnke/jc2pa/GraalWasmEngine.java
package de.christianmahnke.iiif.fliiifenleger.wasm;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.ByteSequence;

import java.io.IOException;

/**
 * {@link WasmEngine} implementation backed by GraalVM's Truffle-based
 * WebAssembly runtime.
 *
 * <p>Only usable when the {@code org.graalvm.polyglot:polyglot} and
 * {@code org.graalvm.polyglot:wasm-community} artifacts are on the classpath
 * (they are declared as optional dependencies).  Check
 * {@link #isAvailable()} before constructing.
 *
 * <p>WASI imports are satisfied by GraalWasm's built-in
 * {@code wasi_snapshot_preview1} module (enabled via the
 * {@code wasm.Builtins} context option).  The module's WASI file access is
 * not backed by any pre-opened directory, so file-system calls will return
 * errors — the c2pa module only uses WASI for randomness, time, and
 * diagnostics.
 */
final class GraalWasmEngine extends WasmEngine {

    /** GraalVM language identifier for WebAssembly. */
    private static final String WASM_LANG = "wasm";

    /** Module name presented to GraalVM when loading the WASM binary. */
    private static final String MODULE_NAME = "c2pa_wasm";

    private final Context graalContext;
    private final Value   wasmBindings;  // exports of the WASM module

    /**
     * @return {@code true} if the GraalVM polyglot artifacts are on the
     *         classpath.
     */
    public static boolean polyglotOnClasspath() {
        try {
            Class.forName("org.graalvm.polyglot.Context");
            Class.forName("org.graalvm.polyglot.io.ByteSequence");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    GraalWasmEngine(byte[] wasmBytes) throws IOException {
        if (!polyglotOnClasspath()) {
            throw new IOException(
                "GraalWasm engine is not available: the GraalVM polyglot "
                + "artifacts are not on the classpath");
        }

        this.graalContext = Context.newBuilder(WASM_LANG)
            // Provide the built-in WASI snapshot preview 1 implementation so
            // the module's wasi_snapshot_preview1 imports resolve.
            .option("wasm.Builtins", "wasi_snapshot_preview1")
            .allowAllAccess(true)
            .build();

        this.wasmBindings = loadModule(graalContext, wasmBytes);
    }

    @Override
    public String name() {
        return GRAALVM;
    }

    @Override
    public boolean isAvailable() {
        return polyglotOnClasspath();
    }

    /**
     * Load a WASM module from raw bytes into a GraalVM {@link Context}.
     *
     * <p>{@code Source.newBuilder} for binary languages requires a
     * {@link ByteSequence}, not a raw {@code byte[]}.  {@link
     * ByteSequence#create(byte[])} wraps the array once.
     *
     * <p>Since polyglot 24-ish, evaluating a WASM source yields a
     * <em>module object</em> that must be instantiated explicitly via
     * {@code newInstance()}; its exports live under the instance's
     * {@code exports} member (older releases auto-instantiated and exposed
     * them under the module name in the language bindings — kept as a
     * fallback).
     */
    private static Value loadModule(Context ctx, byte[] bytes) throws IOException {
        Source source;
        try {
            source = Source.newBuilder(WASM_LANG, ByteSequence.create(bytes),
                                       MODULE_NAME)
                           .build();
        } catch (Exception e) {
            throw new IOException(
                "GraalWasm failed to build module source: " + e.getMessage(), e);
        }
        final Value evaluated;
        try {
            evaluated = ctx.eval(source);
        } catch (Exception e) {
            throw new IOException(
                "GraalWasm failed to parse module: " + e.getMessage(), e);
        }
        if (evaluated.canInstantiate()) {
            try {
                Value exports = evaluated.newInstance().getMember("exports");
                if (exports != null && !exports.isNull()) {
                    return exports;
                }
            } catch (Exception e) {
                throw new IOException(
                    "GraalWasm failed to instantiate module: " + e.getMessage(), e);
            }
            throw new IOException("GraalWasm module instance has no exports member");
        }
        Value named = ctx.getBindings(WASM_LANG).getMember(MODULE_NAME);
        if (named != null && !named.isNull()) {
            return named;
        }
        throw new IOException(
            "GraalWasm module exposes neither an instance exports member nor a '"
            + MODULE_NAME + "' binding");
    }

    @Override
    public int callExport(String name, long... args) {
        Object[] converted = toObjects(args);
        return wasmBindings.getMember(name).execute(converted).asInt();
    }

    @Override
    public void execExport(String name, long... args) {
        wasmBindings.getMember(name).execute(toObjects(args));
    }

    /**
     * Convert i32 arguments: WASM is 32-bit, so every value must arrive as
     * an {@link Integer} — a boxed {@link Long} is rejected by the
     * interpreter ("invalid argument"), and the module's linear memory is a
     * byte array that only accepts {@link Byte} elements (see
     * {@link #writeBytes} / {@link #writeU32}).
     */
    private static Object[] toObjects(long... args) {
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            converted[i] = (int) args[i];
        }
        return converted;
    }

    @Override
    public byte[] readBytes(int address, int length) {
        Value memory = memory();
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = (byte) memory.getArrayElement(address + i).asInt();
        }
        return result;
    }

    @Override
    public String readString(int address, int length) {
        return new String(readBytes(address, length), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public int readU32(int address) {
        Value memory = memory();
        int b0 = memory.getArrayElement(address)     .asInt() & 0xFF;
        int b1 = memory.getArrayElement(address + 1) .asInt() & 0xFF;
        int b2 = memory.getArrayElement(address + 2) .asInt() & 0xFF;
        int b3 = memory.getArrayElement(address + 3) .asInt() & 0xFF;
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    @Override
    public void writeBytes(int address, byte[] data) {
        Value memory = memory();
        for (int i = 0; i < data.length; i++) {
            // Box as Byte: the WASM memory array rejects Integer elements.
            memory.setArrayElement(address + i, data[i]);
        }
    }

    @Override
    public void writeU32(int address, int value) {
        Value memory = memory();
        memory.setArrayElement(address,     (byte)  value);
        memory.setArrayElement(address + 1, (byte) (value >>  8));
        memory.setArrayElement(address + 2, (byte) (value >> 16));
        memory.setArrayElement(address + 3, (byte) (value >> 24));
    }

    private Value memory() {
        return wasmBindings.getMember("memory");
    }

    @Override
    public void close() {
        // Context#close does not throw checked exceptions in this polyglot
        // version; runtime failures on close must not mask the outcome of
        // the operations performed before.
        graalContext.close();
    }
}
