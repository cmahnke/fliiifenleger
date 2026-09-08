// src/test/java/de/christianmahnke/jc2pa/TileSignerTest.java
package de.christianmahnke.jc2pa;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TileSigner}.
 */
@DisplayName("TileSigner")
class TileSignerTest {

    private static final String MANIFEST_JSON = """
        {
          "claim_generator": "jc2pa-test/0.1",
          "title": "Signed Tile",
          "assertions": []
        }
        """;

    private static C2paWasm wasm;

    @BeforeAll
    static void loadWasm() throws IOException {
        // One process-wide instance only — see TestWasmSupport.
        wasm = TestWasmSupport.shared();
    }

    private static byte[] fixture(String name) throws IOException {
        return TestWasmSupport.fixture(name);
    }

    // ── Ephemeral signing ─────────────────────────────────────────────────────

    @Test
    @DisplayName("signEphemeral produces a tile with a readable manifest")
    void signEphemeralProducesReadableManifest() throws Exception {
        byte[] jpeg = fixture("success.jpg");
        try (TileSigner signer = new TileSigner(wasm)) {
            byte[] signed = signer.signEphemeral(jpeg, "image/jpeg",
                                                 MANIFEST_JSON, "tile-signer-test");
            assertThat(signed).isNotEmpty();

            // Validate through the signer: all WASM access must stay on the
            // dedicated signer thread.
            assertThat(signer.activeLabel(signed, "image/jpeg")).isNotBlank();
            assertThat(signer.manifestJson(signed, "image/jpeg")).contains("Signed Tile");
        }
    }

    @Test
    @DisplayName("the shared signer can sign repeatedly without state leakage")
    void repeatedSigningWorks() throws Exception {
        byte[] jpeg = fixture("success.jpg");
        try (TileSigner signer = new TileSigner(wasm)) {
            for (int i = 0; i < 3; i++) {
                byte[] signed = signer.signEphemeral(jpeg, "image/jpeg",
                                                     MANIFEST_JSON, "tile-signer-test");
                assertThat(signed).isNotEmpty();
                assertThat(signer.activeLabel(signed, "image/jpeg")).isNotBlank();
            }
        }
    }

    // ── Key-based signing ─────────────────────────────────────────────────────

    @Test
    @DisplayName("sign with invalid PEM key material throws C2paException")
    void signWithInvalidKeysThrows() throws Exception {
        byte[] jpeg = fixture("success.jpg");
        try (TileSigner signer = new TileSigner(wasm)) {
            assertThatThrownBy(() ->
                signer.sign(jpeg, "image/jpeg", MANIFEST_JSON,
                            "not a cert".getBytes(), "not a key".getBytes(),
                            "es256", null))
                .isInstanceOf(C2paException.class);
        }
    }

    // ── Concurrency ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("concurrent signing is serialized and all tiles validate")
    void concurrentSigningProducesValidTiles() throws Exception {
        byte[] jpeg = fixture("success.jpg");
        int threads = 4;
        int tilesPerThread = 2;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<byte[]>>> futures = new ArrayList<>();

        try (TileSigner signer = new TileSigner(wasm)) {
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    List<byte[]> results = new ArrayList<>();
                    for (int i = 0; i < tilesPerThread; i++) {
                        results.add(signer.signEphemeral(jpeg, "image/jpeg",
                                                         MANIFEST_JSON,
                                                         "concurrent-test"));
                    }
                    return results;
                }));
            }
            start.countDown();

            int validated = 0;
            for (Future<List<byte[]>> f : futures) {
                for (byte[] signed : f.get()) {
                    assertThat(signer.activeLabel(signed, "image/jpeg")).isNotBlank();
                validated++;
                }
            }
            assertThat(validated).isEqualTo(threads * tilesPerThread);
        } finally {
            executor.shutdown();
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sign after close throws IllegalStateException")
    void signAfterCloseThrows() throws Exception {
        TileSigner signer = new TileSigner(wasm);
        signer.close();
        assertThatThrownBy(() ->
            signer.signEphemeral(new byte[1], "image/jpeg", MANIFEST_JSON, "x"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("double close is idempotent")
    void doubleCloseIsIdempotent() {
        TileSigner signer = new TileSigner(wasm);
        signer.close();
        signer.close();
    }

    @Test
    @DisplayName("a shared instance stays usable after its signer is closed")
    void sharedInstanceStaysUsableAfterSignerClose() throws Exception {
        // Note: creating a SECOND live C2paWasm instance corrupts c2pa's
        // process-global state, so owning signers (TileSigner(String)) must
        // not be mixed with a shared instance — verified here by only using
        // the process-wide shared instance.
        byte[] jpeg = fixture("success.jpg");

        TileSigner sharer = new TileSigner(wasm);
        sharer.close();

        TileSigner second = new TileSigner(wasm);
        byte[] signed = second.signEphemeral(jpeg, "image/jpeg", MANIFEST_JSON,
                                             "ownership-test");
        assertThat(signed).isNotEmpty();
        assertThat(second.activeLabel(signed, "image/jpeg")).isNotBlank();
        second.close();
    }
}
