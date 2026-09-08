// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// UltraHDR WASM wrapper.
//
// Exposes a container-level C ABI over the pure Rust ultrahdr-rs codec:
//
//   decode(uhdr_jpeg)         -> handle
//   decode_primary(handle)    -> primary JPEG bytes
//   decode_gainmap(handle)    -> gainmap JPEG bytes
//   decode_metadata(handle)   -> gainmap metadata as JSON
//   decode_free(handle)
//
//   encode(primary_jpeg, gainmap_jpeg, metadata_json) -> UHDR JPEG
//
//   + the standard memory contract: wasm_alloc / wasm_free (+ uhdr_free alias)
//   + version reporting:  uhdr_version
//
// All public functions use `#[no_mangle] pub extern "C"` with a raw-pointer
// ABI.  There is no dependency on wasm_bindgen, js_sys, or web_sys.
//
// Error contract (matches the jc2pa wrapper): every fallible function takes
// `err_ptr`/`err_len` out-parameters; on success the error pointer is null,
// on failure it receives a heap string the host must free with
// `uhdr_free(ptr, len)`.

use ultrahdr_rs as uh;

/// Opaque handle width: i32 inside wasm32 (the Java ABI's pointer width),
/// isize on native targets so 64-bit heap pointers never truncate in tests.
#[cfg(target_arch = "wasm32")]
type RawHandle = i32;
#[cfg(not(target_arch = "wasm32"))]
type RawHandle = isize;

/// Box a value and return it as an opaque handle.
fn into_handle<T>(val: T) -> RawHandle {
    Box::into_raw(Box::new(val)) as RawHandle
}

/// Rebuild the boxed value from an opaque handle.
///
/// # Safety
/// `handle` must have been produced by `into_handle` and not yet freed.
unsafe fn from_handle<T>(handle: RawHandle) -> Box<T> {
    Box::from_raw(handle as *mut T)
}

// ---------------------------------------------------------------------------
// Error type
// ---------------------------------------------------------------------------

#[derive(thiserror::Error, Debug)]
enum UhdrError {
    #[error(transparent)]
    Uhdr(#[from] uh::Error),

    #[error(transparent)]
    SerdeJson(#[from] serde_json::Error),

    #[error(transparent)]
    Other(Box<dyn std::error::Error>),
}

impl UhdrError {
    pub(crate) fn other(e: impl std::error::Error + 'static) -> Self {
        UhdrError::Other(Box::new(e))
    }
}

impl From<UhdrError> for String {
    fn from(value: UhdrError) -> Self {
        format!("{value:?}")
    }
}

// ---------------------------------------------------------------------------
// Memory contract
// ---------------------------------------------------------------------------

/// Allocate `size` zeroed bytes; ownership transfers to the host, which MUST
/// free the buffer with `uhdr_free(ptr, size)`.
#[no_mangle]
pub extern "C" fn wasm_alloc(size: u32) -> *mut u8 {
    if size == 0 {
        // Avoid zero-size allocation UB by allocating at least one byte.
        return wasm_alloc(1);
    }
    let mut v: Vec<u8> = vec![0u8; size as usize];
    let ptr = v.as_mut_ptr();
    std::mem::forget(v);
    ptr
}

/// Free `size` bytes previously allocated by `wasm_alloc` or returned by any
/// output-producing function in this module.
#[no_mangle]
pub extern "C" fn uhdr_free(ptr: *mut u8, size: u32) {
    if ptr.is_null() || size == 0 {
        return;
    }
    // SAFETY: ptr was allocated by Rust's global allocator with capacity `size`.
    unsafe {
        let _ = Vec::from_raw_parts(ptr, size as usize, size as usize);
    }
}

/// Canonical memory-contract name alias — hosts bind either wasm_free or
/// uhdr_free; both are identical.
#[no_mangle]
pub extern "C" fn wasm_free(ptr: *mut u8, size: u32) {
    uhdr_free(ptr, size)
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/// Move a `Vec<u8>` to the heap and write its length to `*out_len`.
/// Host MUST free with `uhdr_free(ptr, len)`.
fn vec_to_host(mut data: Vec<u8>, out_len: *mut u32) -> *mut u8 {
    data.shrink_to_fit();
    let len = data.len() as u32;
    let ptr = data.as_mut_ptr();
    std::mem::forget(data);
    // SAFETY: out_len is a valid writable pointer supplied by the host.
    unsafe { *out_len = len; }
    ptr
}

/// Interpret a `(ptr, len)` pair as a UTF-8 string slice.
///
/// # Safety
/// Memory at `ptr` must contain valid UTF-8 and remain live for `'a`.
unsafe fn ptr_to_str<'a>(ptr: *const u8, len: u32) -> Result<&'a str, UhdrError> {
    let slice = std::slice::from_raw_parts(ptr, len as usize);
    std::str::from_utf8(slice).map_err(UhdrError::other)
}

/// Interpret a `(ptr, len)` pair as a byte slice.
///
/// # Safety
/// Memory at `ptr` must remain live for `'a`.
unsafe fn ptr_to_slice<'a>(ptr: *const u8, len: u32) -> &'a [u8] {
    std::slice::from_raw_parts(ptr, len as usize)
}

/// Write an error message into the host-provided output pointers.
fn write_error(msg: &str, err_ptr: *mut *mut u8, err_len: *mut u32) {
    let bytes = msg.as_bytes().to_vec();
    let len = bytes.len() as u32;
    // SAFETY: pointers are caller-supplied and assumed valid.
    unsafe {
        let mut tmp: u32 = 0;
        let p = vec_to_host(bytes, &mut tmp);
        *err_ptr = p;
        *err_len = len;
    }
}

/// Clear any previous error in the host-provided output pointers.
fn clear_error(err_ptr: *mut *mut u8, err_len: *mut u32) {
    // SAFETY: pointers are caller-supplied and assumed valid.
    unsafe {
        *err_ptr = std::ptr::null_mut();
        *err_len = 0;
    }
}

// ---------------------------------------------------------------------------
// JSON metadata mapping
// ---------------------------------------------------------------------------

/// JSON mirror of `ultrahdr_core::GainMapMetadata` (camelCase, matching the
/// ISO 21496-1 / Android field naming used in the Java layer).
#[derive(serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct MetadataJson {
    gain_map_max: [f64; 3],
    gain_map_min: [f64; 3],
    gamma: [f64; 3],
    base_offset: [f64; 3],
    alternate_offset: [f64; 3],
    base_hdr_headroom: f64,
    alternate_hdr_headroom: f64,
    use_base_color_space: bool,
    backward_direction: bool,
}

impl From<&uh::GainMapMetadata> for MetadataJson {
    fn from(m: &uh::GainMapMetadata) -> Self {
        Self {
            gain_map_max: m.gain_map_max,
            gain_map_min: m.gain_map_min,
            gamma: m.gamma,
            base_offset: m.base_offset,
            alternate_offset: m.alternate_offset,
            base_hdr_headroom: m.base_hdr_headroom,
            alternate_hdr_headroom: m.alternate_hdr_headroom,
            use_base_color_space: m.use_base_color_space,
            backward_direction: m.backward_direction,
        }
    }
}

impl From<&MetadataJson> for uh::GainMapMetadata {
    fn from(j: &MetadataJson) -> Self {
        // GainMapMetadata is #[non_exhaustive] — build via Default + setters.
        let mut m = uh::GainMapMetadata::default();
        m.gain_map_max = j.gain_map_max;
        m.gain_map_min = j.gain_map_min;
        m.gamma = j.gamma;
        m.base_offset = j.base_offset;
        m.alternate_offset = j.alternate_offset;
        m.base_hdr_headroom = j.base_hdr_headroom;
        m.alternate_hdr_headroom = j.alternate_hdr_headroom;
        m.use_base_color_space = j.use_base_color_space;
        m.backward_direction = j.backward_direction;
        m
    }
}

// ---------------------------------------------------------------------------
// Decode handle
// ---------------------------------------------------------------------------

struct DecodeHandle {
    /// The full UHDR input — the decoder borrows slices from it, so the
    /// handle owns the bytes and re-scans per accessor.
    data: Vec<u8>,
}

/// Split a UHDR JPEG into its primary image, gain map and metadata.
///
/// # Returns
/// Opaque i32 handle on success, `0` on failure.
#[no_mangle]
pub extern "C" fn uhdr_decode(
    data_ptr: *const u8,
    data_len: u32,
    err_ptr: *mut *mut u8,
    err_len: *mut u32,
) -> RawHandle {
    let bytes = unsafe { ptr_to_slice(data_ptr, data_len) }.to_vec();
    match uh::Decoder::new(&bytes) {
        Ok(decoder) => {
            if decoder.primary_jpeg().is_none() || decoder.gainmap_jpeg().is_none() {
                write_error(
                    "not an UltraHDR image: primary or gain map missing",
                    err_ptr,
                    err_len,
                );
                return 0;
            }
            clear_error(err_ptr, err_len);
            into_handle(DecodeHandle { data: bytes })
        }
        Err(e) => {
            write_error(&UhdrError::from(e).to_string(), err_ptr, err_len);
            0
        }
    }
}

/// Free a decode handle.
#[no_mangle]
pub extern "C" fn uhdr_decode_free(handle: RawHandle) {
    if handle != 0 {
        // SAFETY: handle was produced by uhdr_decode.
        drop(unsafe { from_handle::<DecodeHandle>(handle) });
    }
}

/// Rebuild a decoder for the given handle.
///
/// # Safety
/// `handle` must be a live decode handle.
unsafe fn decoder_for(handle: RawHandle) -> uh::Decoder<'static> {
    let dh = &*(handle as *const DecodeHandle);
    // The bytes are owned by the (stable, heap-boxed) DecodeHandle and
    // outlive the call, so the decoder's borrowed lifetime is 'static.
    let bytes: &'static [u8] = std::slice::from_raw_parts(dh.data.as_ptr(), dh.data.len());
    uh::Decoder::new(bytes).expect("previously validated UHDR data must re-parse")
}

/// Return the primary image JPEG bytes.
///
/// # Returns
/// Pointer to bytes (host must free), or null on failure.
#[no_mangle]
pub extern "C" fn uhdr_decode_primary(
    handle: RawHandle,
    out_len: *mut u32,
    err_ptr: *mut *mut u8,
    err_len: *mut u32,
) -> *mut u8 {
    if handle == 0 {
        write_error("null decode handle", err_ptr, err_len);
        return std::ptr::null_mut();
    }
    let decoder = unsafe { decoder_for(handle) };
    match decoder.primary_jpeg() {
        Some(jpeg) => {
            clear_error(err_ptr, err_len);
            vec_to_host(jpeg.to_vec(), out_len)
        }
        None => {
            write_error("no primary image", err_ptr, err_len);
            std::ptr::null_mut()
        }
    }
}

/// Return the gain map image JPEG bytes.
///
/// # Returns
/// Pointer to bytes (host must free), or null on failure.
#[no_mangle]
pub extern "C" fn uhdr_decode_gainmap(
    handle: RawHandle,
    out_len: *mut u32,
    err_ptr: *mut *mut u8,
    err_len: *mut u32,
) -> *mut u8 {
    if handle == 0 {
        write_error("null decode handle", err_ptr, err_len);
        return std::ptr::null_mut();
    }
    let decoder = unsafe { decoder_for(handle) };
    match decoder.gainmap_jpeg() {
        Some(jpeg) => {
            clear_error(err_ptr, err_len);
            vec_to_host(jpeg.to_vec(), out_len)
        }
        None => {
            write_error("no gain map image", err_ptr, err_len);
            std::ptr::null_mut()
        }
    }
}

/// Return the gain map metadata as JSON (camelCase fields).
///
/// # Returns
/// Pointer to JSON bytes (host must free), or null on failure.
#[no_mangle]
pub extern "C" fn uhdr_decode_metadata(
    handle: RawHandle,
    out_len: *mut u32,
    err_ptr: *mut *mut u8,
    err_len: *mut u32,
) -> *mut u8 {
    if handle == 0 {
        write_error("null decode handle", err_ptr, err_len);
        return std::ptr::null_mut();
    }
    let decoder = unsafe { decoder_for(handle) };
    match decoder.metadata() {
        Some(metadata) => match serde_json::to_string(&MetadataJson::from(metadata)) {
            Ok(json) => {
                clear_error(err_ptr, err_len);
                vec_to_host(json.into_bytes(), out_len)
            }
            Err(e) => {
                write_error(&UhdrError::from(e).to_string(), err_ptr, err_len);
                std::ptr::null_mut()
            }
        },
        None => {
            write_error("no gain map metadata", err_ptr, err_len);
            std::ptr::null_mut()
        }
    }
}

/// Free a decode handle (canonical memory-contract naming).
#[no_mangle]
pub extern "C" fn decode_free(handle: RawHandle) {
    uhdr_decode_free(handle)
}

// ---------------------------------------------------------------------------
// Encode
// ---------------------------------------------------------------------------

/// Assemble a UHDR JPEG from a primary JPEG, a gain map JPEG and gain map
/// metadata JSON.
///
/// # Parameters
/// - `primary_ptr` / `primary_len` : Primary (SDR) image JPEG bytes.
/// - `gainmap_ptr` / `gainmap_len` : Gain map image JPEG bytes.
/// - `metadata_ptr` / `metadata_len`: Gain map metadata JSON (camelCase).
/// - `base_quality` / `gainmap_quality`: JPEG quality (1–100) the codec uses
///                                      when re-encoding base and gain map.
/// - `out_len`                     : Receives byte length of the result.
/// - `err_ptr` / `err_len`         : Error output.
///
/// # Returns
/// Pointer to the UHDR JPEG (host must free), or null on failure.
#[no_mangle]
pub extern "C" fn uhdr_encode(
    primary_ptr:   *const u8,
    primary_len:   u32,
    gainmap_ptr:   *const u8,
    gainmap_len:   u32,
    metadata_ptr:  *const u8,
    metadata_len:  u32,
    base_quality:  u32,
    gainmap_quality: u32,
    out_len:       *mut u32,
    err_ptr:       *mut *mut u8,
    err_len:       *mut u32,
) -> *mut u8 {
    // ── Parse inputs ─────────────────────────────────────────────────────────
    let primary = unsafe { ptr_to_slice(primary_ptr, primary_len) }.to_vec();
    let gainmap = unsafe { ptr_to_slice(gainmap_ptr, gainmap_len) }.to_vec();
    let metadata_str = unsafe {
        match ptr_to_str(metadata_ptr, metadata_len) {
            Ok(s) => s.to_string(),
            Err(e) => {
                write_error(&e.to_string(), err_ptr, err_len);
                return std::ptr::null_mut();
            }
        }
    };

    let metadata_json: MetadataJson = match serde_json::from_str(&metadata_str) {
        Ok(j) => j,
        Err(e) => {
            write_error(&UhdrError::from(e).to_string(), err_ptr, err_len);
            return std::ptr::null_mut();
        }
    };

    let mut encoder = uh::Encoder::new();
    encoder
        .set_base_jpeg(primary)
        .set_gainmap_jpeg(gainmap, uh::GainMapMetadata::from(&metadata_json))
        .set_quality(base_quality as u8, gainmap_quality as u8);

    match encoder.encode_from_jpegs() {
        Ok(uhdr) => {
            clear_error(err_ptr, err_len);
            vec_to_host(uhdr, out_len)
        }
        Err(e) => {
            write_error(&UhdrError::from(e).to_string(), err_ptr, err_len);
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// Utility exports
// ---------------------------------------------------------------------------

/// Return the ultrahdr-rs codec version string (extracted from Cargo.lock at
/// build time).
///
/// # Returns
/// Heap-allocated UTF-8 string.  Host must free with `uhdr_free(ptr, len)`.
#[no_mangle]
pub extern "C" fn uhdr_version(out_len: *mut u32) -> *mut u8 {
    vec_to_host(env!("UHDR_RS_VERSION").as_bytes().to_vec(), out_len)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// A minimal valid 8x8 JPEG used as both primary and gain map image.
    const TINY_JPEG_B64: &str = "/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAACKADAAQAAAABAAAACAAAAAD/7QA4UGhvdG9zaG9wIDMuMAA4QklNBAQAAAAAAAA4QklNBCUAAAAAABDUHYzZjwCyBOmACZjs+EJ+/8AAEQgACAAIAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/bAEMAAgICAgICAwICAwUDAwMFBgUFBQUGCAYGBgYGCAoICAgICAgKCgoKCgoKCgwMDAwMDA4ODg4ODw8PDw8PDw8PD//bAEMBAgICBAQEBwQEBxALCQsQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEP/dAAQAAf/aAAwDAQACEQMRAD8A4+iiiv6sP5rP/9k=";

    const METADATA_JSON: &str = r#"{
        "gainMapMax": [2.0, 2.0, 2.0],
        "gainMapMin": [0.0, 0.0, 0.0],
        "gamma": [1.0, 1.0, 1.0],
        "baseOffset": [0.015625, 0.015625, 0.015625],
        "alternateOffset": [0.015625, 0.015625, 0.015625],
        "baseHdrHeadroom": 0.0,
        "alternateHdrHeadroom": 1.0,
        "useBaseColorSpace": true,
        "backwardDirection": false
    }"#;

    fn base64_decode(s: &str) -> Vec<u8> {
        // Tiny local base64 decoder — avoids a dependency for one fixture.
        fn val(c: u8) -> u32 {
            match c {
                b'A'..=b'Z' => (c - b'A') as u32,
                b'a'..=b'z' => (c - b'a' + 26) as u32,
                b'0'..=b'9' => (c - b'0' + 52) as u32,
                b'+' => 62,
                b'/' => 63,
                _ => panic!("invalid base64"),
            }
        }
        let bytes = s.trim_end_matches('=').as_bytes();
        let mut out = Vec::with_capacity(bytes.len() * 3 / 4);
        for chunk in bytes.chunks(4) {
            let mut acc = 0u32;
            for (i, c) in chunk.iter().enumerate() {
                acc |= val(*c) << (18 - 6 * i);
            }
            out.push((acc >> 16) as u8);
            if chunk.len() > 2 {
                out.push((acc >> 8) as u8);
            }
            if chunk.len() > 3 {
                out.push(acc as u8);
            }
        }
        out
    }

    /// Full round-trip: assemble → split → verify metadata.
    #[test]
    fn assemble_and_split_round_trip() {
        let primary = base64_decode(TINY_JPEG_B64);
        let gainmap = base64_decode(TINY_JPEG_B64);

        // Assemble.
        let mut encoder = uh::Encoder::new();
        let metadata: uh::GainMapMetadata =
            (&serde_json::from_str::<MetadataJson>(METADATA_JSON).unwrap()).into();
        encoder
            .set_base_jpeg(primary.clone())
            .set_gainmap_jpeg(gainmap.clone(), metadata);
        let uhdr = encoder.encode_from_jpegs().expect("assembly must succeed");

        // Split.
        let handle = into_handle(DecodeHandle { data: uhdr });
        let decoder = unsafe { decoder_for(handle) };

        // Note: the codec re-encodes the base/gain map images at its
        // configured quality — byte-equality with the inputs does not hold.
        let primary_out = decoder.primary_jpeg().expect("primary must be present");
        let gainmap_out = decoder.gainmap_jpeg().expect("gain map must be present");
        let metadata = decoder.metadata().expect("metadata must be present");

        assert!(!primary_out.is_empty(), "primary JPEG must not be empty");
        assert!(!gainmap_out.is_empty(), "gain map JPEG must not be empty");
        assert_eq!(metadata.gain_map_max, [2.0, 2.0, 2.0]);
        assert_eq!(metadata.alternate_hdr_headroom, 1.0);
        uhdr_decode_free(handle);
    }
}
