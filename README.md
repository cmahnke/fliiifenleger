# Fliiifenleger

![Logo](./Fliiifenleger.svg)

`fliiifenleger` is a Java-based command-line tool for generating and validating static IIIF (International Image Interoperability Framework) images. It can process local image files, create IIIF-compliant tile structures, and validate existing IIIF endpoints.

## Table of Contents

- Features
- Architecture
- Prerequisites
- Building from Source
- Installation
- Usage
- Commands and Options
  - `generate`
  - `validate`
  - `info`
- Advanced Usage

## Architecture

The project is a multi-module Maven project with a `core` and a `cli` module.

1.  **Core Module (`core`)**: This module contains the main business logic for IIIF processing.
    *   `ImageSource`: An interface for reading different source image formats (e.g., `DefaultImageSource`, `JxlImageSource`).
    *   `TileSink`: An interface for writing image tiles to different destinations (e.g., `DefaultTileSink` for the local filesystem).
    *   `Tiler`: The central class that orchestrates the process of reading a source image, calculating tile layouts, and writing the tiles and `info.json` using a `TileSink`.
    *   `IiifImageReassembler`: A debug/validation utility to reconstruct a full image from a remote IIIF endpoint.

2.  **CLI Module (`cli`)**: This module provides the command-line interface.
    *   It uses the **picocli** library to define commands, subcommands, and options.
    *   The `Main.java` class is the entry point, defining the main `fliiifenleger` command and its subcommands: `generate`, `validate`, and `info`.
    *   Each subcommand is implemented as a `Callable` class that parses its specific options and calls the appropriate logic in the `core` module.

The use of `java.util.ServiceLoader` (via `@AutoService`) allows for the dynamic discovery of `ImageSource` and `TileSink` implementations at runtime.

## Prerequisites

*   Java JDK 9 or newer
*   Apache Maven 3.x

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

## Installation

After building, the executable JAR will be located at `cli/target/fliiifenleger-cli.jar`. You can run it directly with `java -jar`.

For convenience, you can create an alias or a shell script to make it easier to run from any directory.

**Example alias for `.bashrc` or `.zshrc`:**
```sh
alias fliiifenleger='java -jar cli/target/fliiifenleger-cli.jar'
```

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
| `--sink <name>` | | The image sink implementation to use for tiles. Available: `default`, `ultrahdr` (tiles with integrated gain maps), `c2pa` (C2PA-signed tiles). | `default` |
| `--sink-opt <k=v>` | | Set an option for the image sink (e.g., --sink-opt key=value). | |
| `--source <name>` | `-s` | The image source implementation to use. Available: `default`, `ultrahdr` (UltraHDR JPEGs with gain maps), `jxl`, … | `default` |
| `--source-opt <k=v>` | | Set an option for the image source (e.g., --source-opt key=value). | |
| `--tile-size <size>` | `-t` | Set the tile size. | `1024` |
| `--zoom-levels <num>` | `-z` | Set the number of zoom levels. Set to `0` to auto-calculate. | `0` |

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

## UltraHDR (gain map) tiling

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
* Options: `delegate` (delegate sink, default `default`), `runtime` (WASM
  engine, default `auto`), `quality` (primary re-encode, default `90`),
  `gainmap-quality` (default `85`).

**Technical notes:** like C2PA, the gain map codec lives in its own module
(`ultrahdr`): the pure-Rust [`ultrahdr-rs`](https://github.com/imazen/ultrahdr)
codec is compiled to WebAssembly (`wasm32-wasip1`, ~200 KB) and executed
through the shared `wasm-runtime` layer (pure-JVM Chicory by default).  The
two codec modules (`jc2pa`, `ultrahdr`) are independent — each keeps exactly
one live WASM instance per JVM.

## C2PA Content Credentials

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

Without `cert`/`key`, tiles are signed with an **ephemeral self-signed
certificate** — useful for tests and demos, but the manifests will not
validate against any trust list. For production signing, supply a real
certificate chain and private key, and validate against the
[C2PA trust list](https://opensource.contentauthenticity.org/docs/conformance/trust-lists).

**Technical notes:**
* The C2PA functionality lives in the `jc2pa` module: the Rust
  [c2pa-rs](https://github.com/contentauth/c2pa-rs) SDK is compiled to a
  WebAssembly module (`wasm32-wasip1`) and executed on the pure-JVM
  [Chicory](https://chicory.dev/) runtime — no native dependencies.
* On a GraalVM runtime, `--sink-opt runtime=graalvm` (or
  `-Djc2pa.engine=graalvm`) switches to GraalWasm; `auto` (default) picks it
  only when running on a GraalVM with the polyglot artifacts present and
  falls back to Chicory otherwise.
* All WASM access is routed through a single dedicated thread; concurrent
  tile generation is queued through it.
* Known limitation: signing assets that already carry a C2PA manifest store
  can trip a Chicory interpreter edge case (fresh tiles are unaffected).
* The `jc2pa` module is self-contained: `jc2pa-*-standalone.jar` embeds the
  compiled WASM module and offers the same operations from the command line
  (`version`, `read`, `label`, `manifest`, `sign`, `sign-ephemeral`).

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
  Packages, and attaches the standalone JARs to the GitHub release.
