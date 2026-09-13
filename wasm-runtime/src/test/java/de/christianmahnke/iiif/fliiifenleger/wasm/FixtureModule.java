// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// src/test/java/de/christianmahnke/iiif/fliiifenleger/wasm/FixtureModule.java
package de.christianmahnke.iiif.fliiifenleger.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Generates the minimal test fixture module in-memory instead of committing a
 * {@code .wasm} binary to the repo.
 *
 * <p>The module mirrors the memory contract of the real codec modules:
 * one exported linear memory page, a bump-allocator {@code wasm_alloc}
 * (size {@code 0} allocates one byte, freshly allocated memory is zeroed),
 * a no-op {@code wasm_free}, and a {@code test_version} export returning a
 * pointer to the static version string while writing its length to the given
 * out-parameter slot. It deliberately has no imports (in particular no WASI),
 * so engine tests exercise only instantiation, memory access and calls.
 */
final class FixtureModule {

    static final String VERSION = "1.2.3";
    static final int VERSION_OFFSET = 1024;
    static final int HEAP_START = 2048;

    private static final int I32 = 0x7F;

    private FixtureModule() {
    }

    static byte[] bytes() {
        ByteArrayOutputStream module = new ByteArrayOutputStream();
        module.writeBytes(new byte[]{0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00});

        // Type section: 0: (i32)->i32 (alloc, version), 1: (i32,i32)->() (free).
        ByteArrayOutputStream types = new ByteArrayOutputStream();
        writeUleb(types, 2);
        types.writeBytes(new byte[]{0x60, 0x01, I32, 0x01, I32});
        types.writeBytes(new byte[]{0x60, 0x02, I32, I32, 0x00});
        section(module, 1, types);

        // Function section: alloc -> type 0, free -> type 1, version -> type 0.
        section(module, 3, new byte[]{0x03, 0x00, 0x01, 0x00});

        // Memory section: one page, no maximum.
        section(module, 5, new byte[]{0x01, 0x00, 0x01});

        // Global section: mutable i32 heap pointer, starts above static data.
        ByteArrayOutputStream global = new ByteArrayOutputStream();
        global.write(0x01);
        global.write(I32);
        global.write(0x01);
        constI32(global, HEAP_START);
        global.write(0x0B);
        section(module, 6, global.toByteArray());

        // Export section: memory, wasm_alloc, wasm_free, test_version.
        ByteArrayOutputStream exports = new ByteArrayOutputStream();
        writeUleb(exports, 4);
        exportEntry(exports, "memory", 0x02, 0);
        exportEntry(exports, "wasm_alloc", 0x00, 0);
        exportEntry(exports, "wasm_free", 0x00, 1);
        exportEntry(exports, "test_version", 0x00, 2);
        section(module, 7, exports.toByteArray());

        // Code section.
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        writeUleb(code, 3);
        functionBody(code, new byte[]{0x01, 0x01, I32}, allocCode());
        functionBody(code, new byte[]{0x00}, new byte[]{0x0B});
        functionBody(code, new byte[]{0x00}, versionCode());
        section(module, 10, code.toByteArray());

        // Data section: the version string at VERSION_OFFSET.
        byte[] versionBytes = VERSION.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        writeUleb(data, 1);
        data.write(0x00);
        constI32(data, VERSION_OFFSET);
        data.write(0x0B);
        writeUleb(data, versionBytes.length);
        data.writeBytes(versionBytes);
        section(module, 11, data.toByteArray());

        return module.toByteArray();
    }

    /**
     * Bump allocator: {@code n = size == 0 ? 1 : size; ptr = heap; heap += n;
     * return ptr}. One i32 local holds {@code n}.
     */
    private static byte[] allocCode() {
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.write(0x20); code.write(0x00);             // local.get 0
        code.write(0x45);                              // i32.eqz
        code.write(0x04); code.write(I32);             // if (result i32)
        code.write(0x41); code.write(0x01);            //   i32.const 1
        code.write(0x05);                              // else
        code.write(0x20); code.write(0x00);            //   local.get 0
        code.write(0x0B);                              // end
        code.write(0x21); code.write(0x01);            // local.set 1
        code.write(0x23); code.write(0x00);            // global.get 0
        code.write(0x23); code.write(0x00);            // global.get 0
        code.write(0x20); code.write(0x01);            // local.get 1
        code.write(0x6A);                              // i32.add
        code.write(0x24); code.write(0x00);            // global.set 0
        code.write(0x0B);                              // end
        return code.toByteArray();
    }

    /** {@code *out = len; return VERSION_OFFSET}. */
    private static byte[] versionCode() {
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.write(0x20); code.write(0x00);            // local.get 0
        code.write(0x41); writeUleb(code, VERSION.length()); // i32.const len
        code.write(0x36); code.write(0x00); code.write(0x00); // i32.store align=0 offset=0
        constI32(code, VERSION_OFFSET);
        code.write(0x0B);                              // end
        return code.toByteArray();
    }

    private static void functionBody(ByteArrayOutputStream code, byte[] locals, byte[] instructions) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(locals);
        body.writeBytes(instructions);
        writeUleb(code, body.size());
        code.writeBytes(body.toByteArray());
    }

    private static void exportEntry(ByteArrayOutputStream exports, String name, int kind, int index) {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        writeUleb(exports, nameBytes.length);
        exports.writeBytes(nameBytes);
        exports.write(kind);
        writeUleb(exports, index);
    }

    private static void section(ByteArrayOutputStream module, int id, byte[] payload) {
        module.write(id);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeUleb(framed, payload.length);
        framed.writeBytes(payload);
        module.writeBytes(framed.toByteArray());
    }

    private static void section(ByteArrayOutputStream module, int id, ByteArrayOutputStream payload) {
        section(module, id, payload.toByteArray());
    }

    private static void constI32(ByteArrayOutputStream out, int value) {
        out.write(0x41);
        writeSignedLeb(out, value);
    }

    private static void writeUleb(ByteArrayOutputStream out, int value) {
        int remaining = value;
        do {
            int b = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                b |= 0x80;
            }
            out.write(b);
        } while (remaining != 0);
    }

    private static void writeSignedLeb(ByteArrayOutputStream out, int value) {
        int remaining = value;
        boolean more = true;
        while (more) {
            int b = remaining & 0x7F;
            remaining >>= 7;
            boolean signBit = (b & 0x40) != 0;
            if ((remaining == 0 && !signBit) || (remaining == -1 && signBit)) {
                more = false;
            } else {
                b |= 0x80;
            }
            out.write(b);
        }
    }
}
