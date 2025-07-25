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

## Features

*   **IIIF Tile Generation**: Creates a full static IIIF tile tree from source images.
*   **IIIF Endpoint Validation**: Reassembles and saves an image from a remote IIIF endpoint to verify its integrity.
*   **Extensible Architecture**: Supports custom `ImageSource` and `TileSink` implementations for different image formats and storage backends.
*   **Multiple IIIF Versions**: Supports generating `info.json` for both IIIF Image API v2 and v3.
*   **Parallel Processing**: Generates tiles for multiple images in parallel for improved performance.
*   **Configurable Logging**: Adjustable log levels for debugging.

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
| `--sink <name>` | | The image sink implementation to use for tiles. | `default` |
| `--sink-opt <k=v>` | | Set an option for the image sink (e.g., --sink-opt key=value). | |
| `--source <name>` | `-s` | The image source implementation to use. | `default` |
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
