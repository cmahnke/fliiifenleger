# Fliiifenleger

![Logo](./Fliiifenleger.svg)

`fliiifenleger` is a Java-based command-line tool for generating and validating static IIIF (International Image Interoperability Framework) images. It can process local image files, create IIIF-compliant tile structures, and validate existing IIIF endpoints.

> **This software comes without any warranty of any kind.** `fliiifenleger` is a platform for experiments — most notably the `ultrahdr` and `c2pa` modules, which explore HDR imaging and digital provenance (background articles are linked in the respective sections below).

## Table of Contents

- Features
- Architecture
- Prerequisites
- Quick Start
- Building from Source
- Installation
- Maven Artifacts
- Usage
- Commands and Options
  - `generate`
  - `validate`
  - `info`
  - `info.json` validation
- Advanced Usage

## Quick Start

```sh
# Clone the repo (https://github.com/cmahnke/fliiifenleger).
git clone https://github.com/cmahnke/fliiifenleger.git
cd fliiifenleger

# Build everything (needs JDK 23+, Maven 3.x and a Rust toolchain for the
# WASM codecs; use -DskipTests for a faster build without tests).
mvn clean package -DskipTests

# Tile a sample image (IIIF Image API 2 by default, info.json validated).
java -jar cli/target/fliiifenleger-cli.jar generate --validate-info \
  -o ./my-iiif-images /path/to/image.jpg

# Inspect the generated Image API description.
cat ./my-iiif-images/info.json
```

Next steps: try `--iiif-version V3`, the `ultrahdr` / `c2pa` sinks below,
or point `validate` at a served endpoint (see Commands and Options).

## Architecture

The project is a multi-module Maven project (`core`, `wasm-runtime`,
`cli`, `jc2pa`, `ultrahdr`).

1.  **Core Module (`core`)**: This module contains the main business logic for IIIF processing.
    *   `ImageSource`: An interface for reading different source image formats (e.g., `DefaultImageSource`, `JxlImageSource`).
    *   `TileSink`: An interface for writing image tiles to different destinations (e.g., `DefaultTileSink` for the local filesystem).
    *   `TileEnricher`: A pluggable per-tile metadata hook (e.g., `RegionTileEnricher` records the tile region; the `ultrahdr` module adds the gain-map crop).
    *   `ServiceExtension`: A pluggable `info.json` service description (profile/context URIs plus a schema fragment) so validation and merging stay extension-agnostic; implemented by the `jc2pa` (C2PA) and `ultrahdr` (HDR) modules.
    *   `Tiler`: The central class that orchestrates the process of reading a source image, calculating tile layouts, and writing the tiles and `info.json` using a `TileSink`.
    *   `IiifImageReassembler`: A debug/validation utility to reconstruct a full image from a remote IIIF endpoint.
    *   `InfoJsonValidator`: Validates generated or remote `info.json` documents against the bundled JSON Schemas (Image API 2 and 3, plus the HDR/C2PA extensions).

2.  **CLI Module (`cli`)**: This module provides the command-line interface.
    *   It uses the **picocli** library to define commands, subcommands, and options.
    *   The `Main.java` class is the entry point, defining the main `fliiifenleger` command and its subcommands: `generate`, `validate`, and `info`.
    *   Each subcommand is implemented as a `Callable` class that parses its specific options and calls the appropriate logic in the `core` module.

3.  **Codec Modules (`jc2pa`, `ultrahdr`, `wasm-runtime`)**: The C2PA signer and the UltraHDR gain-map codec are pure-Rust libraries compiled to WebAssembly (`wasm32-wasip1`) and executed through the shared `wasm-runtime` layer (pure-JVM Chicory by default, optional GraalWasm). The compiled `.wasm` files are build artifacts, not part of the repo.

The use of `java.util.ServiceLoader` (via `@AutoService`) allows for the dynamic discovery of `ImageSource`, `TileSink`, `TileEnricher`, and `ServiceExtension` implementations at runtime.

## Prerequisites

*   Java JDK 23 or newer to build (the build enforces this; the emitted bytecode targets Java 21, so running needs Java 21+)
*   Apache Maven 3.x
*   Rust toolchain (`cargo` + `rustup`, `wasm32-wasip1` target) to compile the `jc2pa` / `ultrahdr` WASM codecs from source — not needed with `-Dmaven.cargo.skip=true` if prebuilt `.wasm` files are already present
*   No display needed: all tests run headless (`-Djava.awt.headless=true` is set for every test JVM)

### Optional Software

*   **libjxl**: To enable support for JPEG XL (`.jxl`) images, the native `libjxl` library must be installed on your system. This is required for the `JxlImageSource` to function correctly.
    *   On Debian/Ubuntu: `sudo apt-get install libjxl-dev` or `sudo apt-get install libjxl-tools`
    *   On Alpine Linux: `apk add libjxl`
    *   On macOS (with Homebrew): `brew install jpeg-xl`

## Building from Source

1.  **Clone the repository:**
    ```sh
    git clone https://github.com/cmahnke/fliiifenleger.git
    cd fliiifenleger
    ```

2.  **Build the project with Maven:**
    Run the following command from the root directory. This will compile the code, run tests, and create a self-contained executable JAR in `cli/target/`.
    ```sh
    mvn clean package
    ```

    **Useful properties and profiles:**
    * `-DskipTests` — skip the test suite.
    * `-Dmaven.cargo.skip=true` — do not rebuild the WASM modules (requires previously built `c2pa_wasm.wasm` / `ultrahdr_wasm.wasm` files in the respective `src/main/resources/wasm/` directories; the binaries are build artifacts and not part of the repo).
    * `-Dmaven.rustup.skip=true` — also skip the rustup target check.
    * `-Dcargo.path` / `-Drustup.path` — override the cargo/rustup binaries (toolchains not on the `PATH`).
    * Maven profiles: `dev` (debug WASM build), `ci` (Rust tests and clippy), `skip-rust` (skip the Rust build, same requirement as `maven.cargo.skip`) — all in the `jc2pa` module.
    * `-Pnative` (root pom): adds the `jxl-wasm` module (jxl-oxide decoder as WASM, reserved for the future GraalVM-native build) to the reactor — it is not built by default.

## Installation

After building, the executable JAR will be located at `cli/target/fliiifenleger-cli.jar`. You can run it directly with `java -jar`.

For convenience, you can create an alias or a shell script to make it easier to run from any directory.

**Example alias for `.bashrc` or `.zshrc`:**
```sh
alias fliiifenleger='java -jar cli/target/fliiifenleger-cli.jar'
```

## Maven Artifacts

The modules are published as Maven artifacts to
[GitHub Packages](https://github.com/cmahnke/fliiifenleger/packages) —
they are **not** on Maven Central. To consume them, add the repository:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/cmahnke/fliiifenleger</url>
    </repository>
</repositories>
```

GitHub Packages requires authentication even for downloads, with a server
entry whose `id` matches the repository above (use a personal access token
with `read:packages`):

```xml
<!-- ~/.m2/settings.xml -->
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>YOUR_GITHUB_USERNAME</username>
            <password>YOUR_TOKEN</password>
        </server>
    </servers>
</settings>
```

Then depend on the modules you need (see the packages page for available
versions):

```xml
<dependency>
    <groupId>de.christianmahnke.iiif.fliiifenleger</groupId>
    <artifactId>core</artifactId>
    <version>0.3.0</version>
</dependency>
```

Available artifacts: `core` (tiling logic, schemas, validator), `cli`
(command line), `jc2pa` (C2PA signing), `ultrahdr` (gain-map support),
`wasm-runtime` (shared WASM engine layer).

## Usage

The tool is invoked with a command, followed by its specific options.

```sh
java -jar cli/target/fliiifenleger-cli.jar <command> [options]
```

If you run the command without any subcommand, it will display the main help message.

## Commands and Options

### Global Options

These options can be used with any command.

| Option | Alias | Description | Default |
|---|---|---|---|
| `--log-level <level>` | `-L` | Set the log level. Valid values: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`. | `INFO` |
| `--help` | `-h` | Show help message and exit. | |
| `--version` | `-V` | Print version information and exit. | |

### `generate`
Generates IIIF tiles from one or more local image files.

**Usage:** `fliiifenleger generate [OPTIONS] <file1> <file2> ...`

| Option | Alias | Description | Default |
|---|---|---|---|
| `--identifier <id>` | `-i` | Set the identifier in the info.json. | `http://localhost:8887/iiif/` |
| `--iiif-version <ver>` | | Set the IIIF version. Options: `V2`, `V3`. | `V2` |
| `--output <dir>` | `-o` | Directory where the IIIF images are generated. | `iiif` |
| `--sink <name>` | | The image sink implementation to use for tiles. Available: `default` (option: `format`), `ultrahdr` (tiles with integrated gain maps), `c2pa` (C2PA-signed tiles). | `default` |
| `--sink-opt <k=v>` | | Set an option for the image sink (e.g., --sink-opt key=value). | |
| `--source <name>` | `-s` | The image source implementation to use. Available: `default`, `ultrahdr` (UltraHDR JPEGs with gain maps), `iiif` (re-tile a remote IIIF image), `jxl` (JPEG XL, needs libjxl), `stacked` (source chain, see Advanced Usage), `filter` (standalone filter, see Advanced Usage). | `default` |
| `--source-opt <k=v>` | | Set an option for the image source (e.g., --source-opt key=value). | |
| `--tile-size <size>` | `-t` | Set the tile size. | `512` |
| `--zoom-levels <num>` | `-z` | Set the number of zoom levels. Set to `0` to auto-calculate. | `0` |
| `--jobs <num>` | `-j` | Tile-generation worker threads per image. Set to `0` for automatic sizing (`-Dtiler.workers`, else available processors). | `0` |
| `--validate-info` | | Validate the generated info.json against the JSON Schema for the requested IIIF version. Fails the generation on mismatch. | off |

**Example:**
```sh
java -jar cli/target/fliiifenleger-cli.jar generate --output ./my-iiif-images /path/to/image1.jpg /path/to/image2.png
```

### `validate`
Validates a IIIF endpoint by reassembling the image from its tiles and saving it to a file.

**Usage:** `fliiifenleger validate [OPTIONS] <info.json-url>`

| Option | Alias | Description | Default |
|---|---|---|---|
| `--format <fmt>` | `-f` | Output image format (e.g., jpg, png). | `jpg` |
| `--output <path>` | `-o` | **Required.** Path to save the reassembled image. | |
| `--check-c2pa` | | Check every fetched tile for a C2PA manifest. Exit code 2 if any tile has no (valid) manifest. | |
| `--schema <mode>` | | Validate info.json against its JSON Schema before reassembly. Values: `auto` (detect from `@context`), `2`, `3`, `off`. Exit code 1 on mismatch. | `auto` |

**Example:**
```sh
java -jar cli/target/fliiifenleger-cli.jar validate --output reassembled.jpg https://example.com/iiif/2/my-image/info.json
```

### `info`
Displays information about available components.

**Usage:** `java -jar cli/target/fliiifenleger-cli.jar info <subcommand>`

*   `list-sources`: Lists all available image source implementations.
*   `list-sinks`: Lists all available image sink implementations.

**Example:**
```sh
java -jar cli/target/fliiifenleger-cli.jar info list-sources
```

### `info.json` validation

`generate --validate-info` validates each generated `info.json` against the
JSON Schema for the requested IIIF version and fails the generation on
mismatch. `validate` checks the remote `info.json` against its schema
(`--schema auto|2|3|off`, default `auto`) before reassembling the tiles.

The base schemas live in `core/src/main/resources/schema/`:
`image-api-2-info.json` and `image-api-3-info.json` (JSON Schema draft
2020-12). They cover the required IIIF properties; service extensions ship
their own schema fragments in their modules
(`jc2pa/src/main/resources/schema/c2pa-service.json`,
`ultrahdr/src/main/resources/schema/hdr-service.json`), which the validator
composes with the V3 base schema via `allOf` (discovered through the
`ServiceExtension` SPI, so core never names an extension). The V3 fragments
constrain only service entries claiming their profile
(`https://christianmahnke.de/iiif/c2pa/`, with optional `trustAnchor`, and
`https://christianmahnke.de/iiif/hdr/`); the V2 schema models extensions as
plain URIs in the embedded profile `supports` list and rejects namespaced
properties such as `trustAnchor`. The matching JSON-LD contexts live next
to the fragments (`jc2pa` / `ultrahdr` `src/main/resources/context/`).
Validation is implemented in `InfoJsonValidator` (core) and additionally
enforces that a V3 `@context` array ends with the IIIF context. On a
core-only classpath (no extension modules), extension service entries
validate as generic services.

## UltraHDR (gain map) tiling

Background: [HDR IIIF](https://christianmahnke.de/en/post/hdr-iiif/).

UltraHDR JPEGs (ISO 21496-1 gain maps, as produced by current smartphones) can
be tiled so that **every tile remains HDR-capable**: the tile's region of the
gain map is cropped alongside the primary image and re-embedded into the tile.

```sh
java -jar cli/target/fliiifenleger-cli.jar generate \
  --source ultrahdr \
  --sink ultrahdr \
  --sink-opt quality=90 --sink-opt gainmap-quality=85 \
  -o ./hdr-iiif /path/to/ultrahdr.jpg
```

* `--source ultrahdr` splits the source into primary image, gain map and
  metadata; plain JPEGs without a gain map are handled like the `default`
  source (tiles stay SDR).
* `--sink ultrahdr` wraps a delegate sink and assembles each tile with its
  cropped gain map and the original metadata.  Tiles from gain-map-less
  sources pass through unchanged.
* The per-tile gain-map crop is contributed through the generic
  `TileEnricher` SPI (`GainMapTileEnricher` in the `ultrahdr` module), so
  core stays free of HDR-specific code; stacking sinks (e.g. C2PA over
  UltraHDR) advertises both capabilities in `info.json`.
* Options: `delegate` (delegate sink, default `default`), `runtime` (WASM
  engine, default `auto`), `quality` (primary re-encode, default `90`),
  `gainmap-quality` (default `85`), `threads` (parallel assembly lanes,
  default one per available processor, see Parallel WASM lanes).
* The sink advertises HDR capability in `info.json` via
  `https://christianmahnke.de/iiif/hdr/`: a `service` entry (plus
  `extraFeatures` entry and prepended JSON-LD context) for Image API 3, or an
  entry in the embedded profile `supports` list for Image API 2.

**Technical notes:** like C2PA, the gain map codec lives in its own module
(`ultrahdr`): the pure-Rust [`ultrahdr-rs`](https://github.com/imazen/ultrahdr)
codec is compiled to WebAssembly (`wasm32-wasip1`, ~200 KB) and executed
through the shared `wasm-runtime` layer (pure-JVM Chicory by default).  The
two codec modules (`jc2pa`, `ultrahdr`) are independent — each keeps one
live WASM instance per lane (one lane per available processor by default;
see Parallel WASM lanes).

## C2PA Content Credentials

Background: [Digital Provenance](https://christianmahnke.de/en/post/digital-provenance/).

Tiles can be signed with [C2PA](https://c2pa.org/) manifests (Content
Credentials) during generation:

```sh
java -jar cli/target/fliiifenleger-cli.jar generate \
  --sink c2pa \
  --sink-opt cert-name=fliiifenleger \
  -o ./signed-iiif /path/to/image.jpg
```

The `c2pa` sink wraps the `default` sink and signs every tile with a
per-tile manifest that contains the tile's region in source-image
coordinates (`org.projektemacher.iiif.region` assertion).

**Options** (via `--sink-opt key=value`):

| Option | Description | Default |
|---|---|---|
| `delegate` | Name of the delegate sink that renders the tiles. | `default` |
| `format` | Tile format. | `jpg` |
| `runtime` | WASM engine: `auto`, `chicory` (pure-JVM, works everywhere), or `graalvm` (requires the GraalVM polyglot artifacts). | `auto` |
| `cert` / `key` | PEM certificate chain and private key files for real signatures. Both must be given together. | – |
| `alg` | Signing algorithm (`es256`, `ps256`, `ed25519`, …). | `es256` |
| `tsa` | Timestamp authority URL (only with `cert`/`key`). | – |
| `cert-name` | Common name for the ephemeral test certificate (only without `cert`/`key`). | `fliiifenleger` |
| `claim-generator` | Claim generator string written into the manifest. | `fliiifenleger` |
| `trust-anchor` | Absolute URI advertised as `trustAnchor` in the `https://christianmahnke.de/iiif/c2pa/` service entry of a V3 `info.json`. **Requires `--iiif-version V3`** — generation fails fast with Image API 2, which has no place for namespaced options (fixed `@context`). | – |
| `threads` | Parallel signing lanes: `0`/unset follows `-Dwasm.lanes` (default: one per available processor); `1` selects serial execution. | auto |

Every `c2pa` run advertises `https://christianmahnke.de/iiif/c2pa/` in
`info.json`: a `service` entry (plus `extraFeatures` entry and prepended
JSON-LD context) for Image API 3, or an entry in the embedded profile
`supports` list for Image API 2 (only without `trust-anchor`).

Without `cert`/`key`, tiles are signed with an **ephemeral self-signed
certificate** — useful for tests and demos, but the manifests will not
validate against any trust list. For production signing, supply a real
certificate chain and private key, and validate against the
[C2PA trust list](https://opensource.contentauthenticity.org/docs/conformance/trust-lists).

### Signing with your own certificate

```sh
java -jar cli/target/fliiifenleger-cli.jar generate \
  --sink c2pa \
  --sink-opt cert=chain.pem \
  --sink-opt key=key.pem \
  --sink-opt alg=es256 \
  -o ./signed-iiif /path/to/image.jpg
```

Requirements for `cert` / `key` (checked at signing time — a mismatch
fails with `the certificate is invalid`):

* `cert` is a PEM file holding the certificate chain with the
  end-entity certificate first, followed by the intermediate/CA
  certificate(s).
* `key` is the PEM-encoded (PKCS#8) private key matching the
  end-entity certificate.
* `alg` must match the key type: `es256` for EC P-256 keys (default),
  `ps256` for RSA keys (at least 2048 bit), `ed25519` for Ed25519 keys.
* The end-entity certificate must be X.509v3, currently valid, carry
  the `digitalSignature` key usage and an allowed extended key usage
  (e.g. `emailProtection`), plus subject/authority key identifiers, and
  its subject must contain an organization (`O`) attribute — without it,
  validation misleadingly reports `claimSignature.mismatch`. A bare
  self-signed certificate without this structure is rejected.
* `tsa` optionally adds a trusted timestamp (timestamp authority URL).

A throwaway test chain with openssl (EC P-256, test use only —
production credentials come from a CA on the C2PA trust list):

```sh
# Certificate authority (self-signed).
openssl ecparam -name prime256v1 -genkey -noout -out ca-key.pem
openssl req -x509 -new -nodes -key ca-key.pem -sha256 -days 365 \
  -subj "/CN=fliiifenleger-test-ca/O=fliiifenleger-test" \
  -addext "basicConstraints=critical,CA:true" \
  -addext "keyUsage=critical,keyCertSign,digitalSignature,cRLSign" \
  -addext "subjectKeyIdentifier=hash" \
  -addext "authorityKeyIdentifier=keyid:always" \
  -out ca-cert.pem

# End-entity certificate, signed by the CA (key converted to PKCS#8,
# the format the signer expects).
openssl ecparam -name prime256v1 -genkey -noout -out ee-sec1.pem
openssl pkcs8 -topk8 -nocrypt -in ee-sec1.pem -out key.pem
openssl req -new -key key.pem -subj "/CN=fliiifenleger-test/O=fliiifenleger-test" -out ee.csr
printf "basicConstraints=critical,CA:false\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=emailProtection\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n" > ee-ext.cnf
openssl x509 -req -in ee.csr -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial \
  -days 365 -sha256 -extfile ee-ext.cnf -out ee-cert.pem

# Chain file: end-entity first, then CA.
cat ee-cert.pem ca-cert.pem > chain.pem
```

Then check the result — a single tile with the standalone jar, or a
whole endpoint with `validate --check-c2pa`:

```sh
java -jar jc2pa/target/jc2pa-*-standalone.jar validate image/jpeg <tile>.jpg
java -jar cli/target/fliiifenleger-cli.jar validate --check-c2pa \
  -o reassembled.jpg https://example.com/iiif/2/my-image/info.json
```

Self-signed test chains validate structurally (the manifest is present
and parses) but not against the C2PA trust list. The
`C2paSignWithKeysRoundTripTest` in the `jc2pa` module demonstrates the
same flow programmatically (runtime-generated CA + end-entity,
`signWithKeys`, read back with `C2paReader`).

**Technical notes:**
* The C2PA functionality lives in the `jc2pa` module: the Rust
  [c2pa-rs](https://github.com/contentauth/c2pa-rs) SDK is compiled to a
  WebAssembly module (`wasm32-wasip1`) and executed on the pure-JVM
  [Chicory](https://chicory.dev/) runtime — no native dependencies.
* On a GraalVM runtime, `--sink-opt runtime=graalvm` (or
  `-Dwasm.engine=graalvm`) switches to GraalWasm; `auto` (default) picks it
  only when running on a GraalVM with the polyglot artifacts present and
  falls back to Chicory otherwise.  The same option exists on the ultrahdr
  side (`--source-opt runtime=…` / `--sink-opt runtime=…`).
* All WASM access is routed through a lane pool with strict thread-instance
  affinity (see Parallel WASM lanes); a single lane behaves exactly like the
  previous dedicated signing thread.
* Known limitation: signing assets that already carry a C2PA manifest store
  can trip a Chicory interpreter edge case (fresh tiles are unaffected).
* The `jc2pa` module is self-contained: `jc2pa-*-standalone.jar` embeds the
  compiled WASM module and offers the same operations from the command line
  (`version`, `read`, `label`, `manifest`, `validate`, `sign`,
  `sign-ephemeral`).

### Parallel WASM lanes

WASM work (C2PA signing, UltraHDR assembly) runs on one lane per available
processor capped at 4 by default; `threads=1` (or `-Dwasm.lanes=1`) selects
serial execution:

```sh
java -jar cli/target/fliiifenleger-cli.jar generate \
  --sink c2pa \
  --sink-opt threads=2 \
  -o ./signed-iiif /path/to/image.jpg
```

`--sink-opt threads=N` exists on both the `c2pa` and `ultrahdr` sinks;
`-Dwasm.lanes=N` sets the default when unset.  Each lane pairs one
interpreter instance with its own thread (instances are fully isolated, so
manifests can't leak across tiles); requests are clamped to the available
processors (and to 2 lanes for GraalWasm).

Tiles themselves are generated per-tile tasks over a worker pool sized by
`--jobs/-j` (`-Dtiler.workers`, default: available processors), so slow
tiles no longer pin a whole scale level behind them.

Measured on a 10-core laptop (`page011.jpg`, 105 tiles, ephemeral C2PA
signatures): serial (`threads=1`) ~26s, `threads=2` ~14–19s at near-equal
CPU, default (4 lanes here) ~12–13s.  Isolated signing throughput scales
near-linearly: 12 distinct 512px tiles 2.6s → 0.7s (3.8×), one 1.4MB
full-size tile 20.4s → 5.7s (3.6×).  Your mileage varies with tile-size mix
(a few huge tiles dominate the tail) and hardware; beyond ~4 lanes
oversubscription burns CPU and memory without wall gains on this machine
(6 lanes: ~13.4s at +38% CPU), hence the default cap — raise it explicitly
for server hardware.

## Advanced Usage

### Chaining Image Processors with `stacked`

The `stacked` source type allows you to chain multiple image sources and manipulators together. This is powerful for applying a sequence of operations, such as applying a filter to a base image before tiling.

Configuration is best done via a YAML file, which you pass to the `generate` command using the `--source-opt` option.

**Usage:**
```sh
java -jar cli/target/fliiifenleger-cli.jar generate \
  -s stacked \
  --source-opt config=path/to/your/config.yaml
```

#### Example YAML Configuration

This example loads a JPEG, applies a sepia filter, and then generates tiles from the filtered image.

`config.yaml`:
```yaml
sources:
  - type: default
    path: /path/to/your/image.jpg
  - type: filter
    options:
      type: sepia
```

### Re-tiling a remote IIIF image (`iiif`)

The `iiif` source fetches an existing IIIF image by its `info.json` URL and re-tiles it locally — useful to mirror or re-generate tiles from a remote endpoint:

```sh
java -jar cli/target/fliiifenleger-cli.jar generate \
  --source iiif -o ./my-iiif \
  https://example.org/iiif/2/my-image/info.json
```

### Standalone filter source (`filter`)

The filters documented below can also be used directly as an image source instead of inside a `stacked` chain:

```sh
java -jar cli/target/fliiifenleger-cli.jar generate \
  --source filter --source-opt type=sepia \
  -o ./filtered /path/to/image.jpg
```

### Available Filters

The `filter` source type can be used within a `stacked` configuration to apply various image effects.

| Filter `type` | Description | Additional Options |
|---|---|---|
| `grayscale` | Converts the image to grayscale. | |
| `invert` | Inverts the colors of the image. | |
| `sepia` | Applies a sepia tone to the image. | |
| `blur` | Applies a box blur. | `blurRadius=<int>` (Default: 3) |
| `posterize` | Reduces the number of colors in the image. | `posterizeLevels=<int>` (Default: 4) |
| `threshold` | Converts the image to black and white based on a luminance threshold. | `thresholdValue=<int>` (Default: 128) |
| `none` | Applies no filter (passes the image through). | |

#### Filter Example with Options

This configuration applies a blur with a radius of 5 pixels.

```yaml
sources:
  - type: default
    path: /path/to/your/image.jpg
  - type: filter
    options:
      type: blur
      blurRadius: 5
```

## Deployment

GitHub Actions workflows publish the Maven artifacts to GitHub Packages:
* `maven.yml` — on every push to `main` it runs the full test suite and
  deploys the `0.x-SNAPSHOT` artifacts; pull requests are only built and
  tested.
* `release.yml` — on a `v*` tag (e.g. `v0.1.0`) it runs the test suite, sets
  the Maven version from the tag, deploys the release artifacts to GitHub
  Packages, attaches the standalone JARs plus the Homebrew tarball
  (`fliiifenleger.tar.gz`, layout `bin/fliiifenleger` +
  `lib/fliiifenleger-cli.jar`, see `packaging/homebrew/`) to the GitHub
  release, builds GraalVM native binaries on Linux (`amd64` + `arm64`,
  `native` matrix job) and attaches those as
  `fliiifenleger-<version>-linux-<arch>.tar.gz`, and publishes the
  Homebrew formula to the `cmahnke/homebrew-fliiifenleger` tap via
  [`homebrew-releaser`](https://github.com/marketplace/actions/homebrew-releaser).
* `maven-site.yml` — publishes the generated Maven site (this documentation)
  to GitHub Pages on every push to `main`.
* `docker.yml` — builds `Dockerfile` and publishes
  `ghcr.io/cmahnke/fliiifenleger/cli`: on a `v*` tag as `X.Y.Z`, `X.Y` and
  `latest` (with the release version baked into the CLI jar), on `main` as
  `snapshot`; pull requests only validate the build without pushing.
  (`Dockerfile.ubuntu` is local-only, built by no workflow.)

### Changing the project version

The version lives literally in the parent pom plus the `<parent><version>`
reference of every module.  The versions-maven-plugin updates all of them in
one pass:

```sh
mvn --batch-mode org.codehaus.mojo:versions-maven-plugin:2.17.1:set -DnewVersion=0.4.0-SNAPSHOT -DgenerateBackupPoms=false
```

This is the same mechanism the `release.yml` workflow uses (with the tag
version as `-DnewVersion`) before testing and deploying a release.  Snapshot
deploys on `main` need no version change — they publish the current
`0.x-SNAPSHOT` artifacts.

Re-running a release for the same version works out of the box: GitHub
Packages treats release versions as immutable (re-deploying returns
`409 Conflict`), so `release.yml` deletes previously published package
versions of the release before deploying.

### Homebrew

End-user install once a release is published:

```sh
brew tap cmahnke/fliiifenleger
brew install fliiifenleger
fliiifenleger --version
```

The formula installs the shaded CLI JAR into `libexec` with a wrapper
pinned to `openjdk@21` (matching the `release 21` bytecode target) that
keeps `--enable-native-access=ALL-UNNAMED` for the JXL imageio plugin,
and depends on `jpeg-xl` so the `jxl` image source works without extra
setup.  A direct (non-Homebrew) install is the attached
`fliiifenleger.tar.gz`: extract it anywhere and run `bin/fliiifenleger`
(requires Java 21+ on the `PATH`).

Each `v*` release then updates `Formula/fliiifenleger.rb` in the tap
automatically (checksum + URL); no per-release manual steps.

### Native binaries

`mvn -Pnative package` (requires GraalVM 25.3+ as the build JDK — the
enforcer fails fast otherwise, and builder/polyglot versions must match
the pinned 25.3.x artifacts) produces a self-contained `cli/target/fliiifenleger`
binary: no JDK needed at runtime, JXL decodes through the bundled
`jxl-wasm` module (functional but ~5× slower than the native lib), and
Chicory is excluded from the image entirely.

CI builds Linux `amd64`/`arm64` binaries on every `v*` tag (see above);
macOS is served by the JVM artifacts (Homebrew formula), which work on
both Apple Silicon and Intel Macs.

## License

Released under the [MIT License](./LICENSE).

Exception: `jc2pa/src/main/rust/src/lib.rs` is derived from Adobe's
[c2pa-js](https://github.com/contentauth/c2pa-js) (`packages/c2pa-wasm`,
itself MIT-licensed) and retains its original Adobe copyright notice.
