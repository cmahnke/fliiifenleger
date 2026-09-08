

Existing Wasm Module:
*https://github.com/contentauth/c2pa-js/blob/main/packages/c2pa-wasm/

Reuse the interface of c2pa-wasm for our lib, just sync

https://users.rust-lang.org/t/use-wasm-pack-to-create-a-wasm-package-for-java/126264/2

## Architecture

The WASM runtime layer (engine selection, Chicory/GraalWasm, linear-memory
helpers) lives in the shared `wasm-runtime` module.  This module contains
only the c2pa codec bindings; the same pattern is used by the independent
`ultrahdr` module.
