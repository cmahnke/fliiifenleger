// src/main/java/de/christianmahnke/jc2pa/ChicoryEngine.java
package de.christianmahnke.iiif.fliiifenleger.wasm;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link WasmEngine} implementation backed by the Chicory WebAssembly
 * runtime — a pure-JVM interpreter with zero native dependencies.
 *
 * <p>The module's {@code wasi_snapshot_preview1} imports are provided by
 * Chicory's {@link WasiPreview1} host functions.
 */
final class ChicoryEngine extends WasmEngine {

    /** Instance name used when instantiating the module (symbolic only). */
    private static final String MODULE_NAME = "c2pa_wasm";

    private final Store        store;
    private final WasiPreview1 wasi;
    private final Instance     instance;

    private final Map<String, ExportFunction> exports = new HashMap<>();

    ChicoryEngine(byte[] wasmBytes) throws IOException {
        this.wasi = WasiPreview1.builder()
            .withOptions(WasiOptions.builder().inheritSystem().build())
            .build();
        this.store = new Store().addFunction(wasi.toHostFunctions());
        WasmModule module = Parser.parse(wasmBytes);
        this.instance = store.instantiate(MODULE_NAME, module);
    }

    @Override
    public String name() {
        return CHICORY;
    }

    @Override
    public boolean isAvailable() {
        // Chicory is a compile-scope dependency of this module — always present.
        return true;
    }

    private ExportFunction fn(String name) {
        return exports.computeIfAbsent(name, instance::export);
    }

    @Override
    public int callExport(String name, long... args) {
        long[] result = fn(name).apply(args);
        return (int) result[0];
    }

    @Override
    public void execExport(String name, long... args) {
        fn(name).apply(args);
    }

    @Override
    public byte[] readBytes(int address, int length) {
        return instance.memory().readBytes(address, length);
    }

    @Override
    public String readString(int address, int length) {
        return instance.memory().readString(address, length);
    }

    @Override
    public int readU32(int address) {
        return (int) instance.memory().readU32(address);
    }

    @Override
    public void writeBytes(int address, byte[] data) {
        instance.memory().write(address, data);
    }

    @Override
    public void writeU32(int address, int value) {
        instance.memory().write(address, new byte[] {
            (byte)  value,
            (byte) (value >>  8),
            (byte) (value >> 16),
            (byte) (value >> 24)
        });
    }

    @Override
    public void close() {
        wasi.close();
    }
}
