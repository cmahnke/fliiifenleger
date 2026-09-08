// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.jc2pa;

import de.christianmahnke.iiif.fliiifenleger.wasm.WasmMemory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Command-line entry point for jc2pa.
 *
 * <pre>
 * Usage:
 *   jc2pa version
 *   jc2pa read            &lt;format&gt; &lt;asset-file&gt;
 *   jc2pa label           &lt;format&gt; &lt;asset-file&gt;
 *   jc2pa manifest        &lt;format&gt; &lt;asset-file&gt;
 *   jc2pa validate        &lt;format&gt; &lt;asset-file&gt;
 *   jc2pa sign            &lt;format&gt; &lt;asset-file&gt; &lt;manifest-json-file&gt;
 *                         &lt;certs-pem-file&gt; &lt;key-pem-file&gt; &lt;alg&gt; [tsa-url]
 *                         &lt;output-file&gt;
 *   jc2pa sign-ephemeral  &lt;format&gt; &lt;asset-file&gt; &lt;manifest-json-file&gt;
 *                         &lt;cert-name&gt; &lt;output-file&gt;
 * </pre>
 *
 * <p>The WASM module is loaded from the classpath resource
 * {@code /c2pa_wasm.wasm}.  If that is not found, the path
 * {@code ./c2pa_wasm.wasm} in the current working directory is tried next.
 *
 * <p>This makes the shaded jc2pa JAR self-contained: it embeds the compiled
 * {@code c2pa_wasm.wasm} and requires nothing but a JVM.
 */
public class Main {

    private static final PrintStream OUT = System.out;
    private static final PrintStream ERR = System.err;

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        try (C2paWasm wasm = loadWasm()) {
            String command = args[0];
            switch (command) {
                case "version"       -> cmdVersion(wasm);
                case "read"          -> cmdRead(wasm, args);
                case "label"         -> cmdLabel(wasm, args);
                case "manifest"      -> cmdManifest(wasm, args);
                case "validate"      -> cmdValidate(wasm, args);
                case "sign"          -> cmdSign(wasm, args);
                case "sign-ephemeral"-> cmdSignEphemeral(wasm, args);
                default -> {
                    ERR.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
                }
            }
        } catch (C2paException e) {
            ERR.println("c2pa error: " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            ERR.println("I/O error: " + e.getMessage());
            System.exit(3);
        } catch (Exception e) {
            ERR.println("Unexpected error: " + e.getMessage());
            e.printStackTrace(ERR);
            System.exit(4);
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Print the c2pa crate version embedded in the WASM module.
     *
     * <pre>jc2pa version</pre>
     */
    private static void cmdVersion(C2paWasm wasm) {
        WasmMemory mem      = wasm.memory();
        int        outSlot  = mem.allocU32Slot();
        int        ptr      = wasm.c2paVersion(outSlot);
        int        len      = mem.readU32(outSlot);
        String     version  = mem.readString(ptr, len);
        wasm.wasmFree(ptr,    len);
        wasm.wasmFree(outSlot, 4);
        OUT.println("c2pa version: " + version);
    }

    /**
     * Print the full manifest store JSON for an asset.
     *
     * <pre>jc2pa read &lt;format&gt; &lt;asset-file&gt;</pre>
     */
    private static void cmdRead(C2paWasm wasm, String[] args) throws IOException {
        requireArgs(args, 3, "read <format> <asset-file>");
        String format = args[1];
        byte[] data   = readFile(args[2]);

        try (C2paReader reader = C2paReader.fromBytes(wasm, format, data)) {
            OUT.println(reader.json());
        }
    }

    /**
     * Print the active manifest label (or {@code "(none)"} if absent).
     *
     * <pre>jc2pa label &lt;format&gt; &lt;asset-file&gt;</pre>
     */
    private static void cmdLabel(C2paWasm wasm, String[] args) throws IOException {
        requireArgs(args, 3, "label <format> <asset-file>");
        String format = args[1];
        byte[] data   = readFile(args[2]);

        try (C2paReader reader = C2paReader.fromBytes(wasm, format, data)) {
            String label = reader.activeLabel();
            OUT.println(label != null ? label : "(none)");
        }
    }

    /**
     * Print the active manifest as JSON.
     *
     * <pre>jc2pa manifest &lt;format&gt; &lt;asset-file&gt;</pre>
     */
    private static void cmdManifest(C2paWasm wasm, String[] args) throws IOException {
        requireArgs(args, 3, "manifest <format> <asset-file>");
        String format = args[1];
        byte[] data   = readFile(args[2]);

        try (C2paReader reader = C2paReader.fromBytes(wasm, format, data)) {
            OUT.println(reader.activeManifestJson());
        }
    }

    /**
     * Print the C2PA validation state and results for an asset.
     *
     * <pre>jc2pa validate &lt;format&gt; &lt;asset-file&gt;</pre>
     */
    private static void cmdValidate(C2paWasm wasm, String[] args) throws IOException {
        requireArgs(args, 3, "validate <format> <asset-file>");
        String format = args[1];
        byte[] data   = readFile(args[2]);

        try (C2paReader reader = C2paReader.fromBytes(wasm, format, data)) {
            OUT.println("validation state: " + reader.validationState());
            String results = reader.validationResultsJson();
            OUT.println("validation results: " + (results != null ? results : "(none)"));
        }
    }

    /**
     * Sign an asset using a PEM certificate chain and PEM private key.
     *
     * <pre>
     * jc2pa sign &lt;format&gt; &lt;asset-file&gt; &lt;manifest-json-file&gt;
     *            &lt;certs-pem-file&gt; &lt;key-pem-file&gt; &lt;alg&gt; [tsa-url] &lt;output-file&gt;
     * </pre>
     */
    private static void cmdSign(C2paWasm wasm, String[] args) throws IOException {
        requireArgs(args, 8,
            "sign <format> <asset-file> <manifest-json-file> "
            + "<certs-pem-file> <key-pem-file> <alg> [tsa-url] <output-file>");

        String format       = args[1];
        byte[] assetBytes   = readFile(args[2]);
        String manifestJson = new String(readFile(args[3]), StandardCharsets.UTF_8);
        byte[] certsPem     = readFile(args[4]);
        byte[] keyPem       = readFile(args[5]);
        String alg          = args[6];
        String tsaUrl       = args.length > 8 ? args[7] : null;
        Path   outputPath   = Paths.get(args[args.length - 1]);

        try (C2paBuilder builder = new C2paBuilder(wasm, manifestJson)) {
            byte[] signed = builder.signWithKeys(
                format, assetBytes, certsPem, keyPem, alg, tsaUrl);

            Files.write(outputPath, signed);
            OUT.println("Signed asset written to: " + outputPath);
            OUT.println("Output size: " + signed.length + " bytes");
        }
    }

    /**
     * Sign an asset with an ephemeral self-signed certificate (test/demo).
     *
     * <pre>
     * jc2pa sign-ephemeral &lt;format&gt; &lt;asset-file&gt; &lt;manifest-json-file&gt;
     *                      &lt;cert-name&gt; &lt;output-file&gt;
     * </pre>
     */
    private static void cmdSignEphemeral(C2paWasm wasm, String[] args) throws IOException {
        requireArgs(args, 6,
            "sign-ephemeral <format> <asset-file> <manifest-json-file> "
            + "<cert-name> <output-file>");

        String format       = args[1];
        byte[] assetBytes   = readFile(args[2]);
        String manifestJson = new String(readFile(args[3]), StandardCharsets.UTF_8);
        String certName     = args[4];
        Path   outputPath   = Paths.get(args[5]);

        try (C2paBuilder builder = new C2paBuilder(wasm, manifestJson)) {
            byte[] signed = builder.signEphemeral(format, assetBytes, certName);

            Files.write(outputPath, signed);
            OUT.println("Signed asset written to: " + outputPath);
            OUT.println("Output size: " + signed.length + " bytes");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Load the WASM module.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Try the classpath resource {@code /c2pa_wasm.wasm}.</li>
     *   <li>Fall back to {@code ./c2pa_wasm.wasm} in the current directory.</li>
     * </ol>
     */
    private static C2paWasm loadWasm() throws IOException {
        try {
            return C2paWasm.fromClasspath();
        } catch (IOException ignored) {
            // Fall through to file-system fallback.
        }

        Path local = Paths.get("c2pa_wasm.wasm");
        if (Files.exists(local)) {
            return new C2paWasm(local);
        }

        throw new IOException(
            "Cannot locate c2pa_wasm.wasm: add it to the classpath as " +
            "/c2pa_wasm.wasm or place it in the current working directory.");
    }

    private static byte[] readFile(String path) throws IOException {
        return Files.readAllBytes(Paths.get(path));
    }

    private static void requireArgs(String[] args, int minimum, String usage) {
        if (args.length < minimum) {
            ERR.println("Usage: jc2pa " + usage);
            System.exit(1);
        }
    }

    private static void printUsage() {
        OUT.println("""
            Usage: jc2pa <command> [args...]

            Commands:
              version                                          Print c2pa crate version
              read          <format> <asset>                   Print manifest store JSON
              label         <format> <asset>                   Print active manifest label
              manifest      <format> <asset>                   Print active manifest JSON
              validate      <format> <asset>                   Print C2PA validation state/results
              sign          <format> <asset> <manifest.json>   Sign with PEM cert chain + key
                            <certs.pem> <key.pem> <alg>
                            [tsa-url] <output-file>
              sign-ephemeral <format> <asset> <manifest.json>  Sign with ephemeral self-signed cert
                            <cert-name> <output-file>

            format examples:
              image/jpeg   image/png

            alg examples:
              ps256   es256   es384   es512   ed25519

            The WASM module (c2pa_wasm.wasm) must be available as:
              /c2pa_wasm.wasm on the classpath, OR
              ./c2pa_wasm.wasm in the current working directory.
            """);
    }
}
