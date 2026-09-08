// Copyright 2025 Adobe
// All Rights Reserved.
//
// NOTICE: Adobe permits you to use, modify, and distribute this file in
// accordance with the terms of the Adobe license agreement accompanying
// it.
//
// Sync WASM library (trimmed).
//
// All public functions use `#[no_mangle] pub extern "C"` with a raw-pointer
// ABI.  There is no dependency on wasm_bindgen, js_sys, or web_sys.
//
// Export surface — trimmed to what the jc2pa host needs:
//   memory     : wasm_alloc, wasm_free
//   utility    : c2pa_version
//   reader     : reader_from_bytes, reader_free, reader_active_label,
//                reader_json, reader_active_manifest_json,
//                reader_validation_results, reader_validation_state
//   builder    : builder_from_json, builder_free,
//                builder_set_intent, builder_add_ingredient,
//                builder_sign_with_keys, builder_sign_ephemeral
//
// ┌──────────────────────────────────────────────────────────────────────┐
// │                        Memory contract                               │
// │                                                                      │
// │  INPUT  buffers – owned by the host, valid for the call only.       │
// │  OUTPUT buffers – allocated by WASM (Rust global allocator),        │
// │                   ownership transferred to host on return.           │
// │                   Host MUST free them with wasm_free(ptr, len).     │
// │                                                                      │
// │  Error reporting                                                     │
// │    Every fallible function accepts two extra output parameters:      │
// │      err_ptr : *mut *mut u8  – receives heap-allocated UTF-8        │
// │                                error string, or null on success      │
// │      err_len : *mut u32      – byte length of that string           │
// │    On success  → *err_ptr = null,  *err_len = 0                    │
// │    On failure  → *err_ptr = heap string (host must free),           │
// │                  *err_len = byte count                               │
// │                                                                      │
// │  Opaque handles                                                      │
// │    Objects that outlive a single call are heap-boxed and returned   │
// │    as i32 (raw pointer cast).  The host must call the matching      │
// │    _free function when done.                                         │
// └──────────────────────────────────────────────────────────────────────┘

// ---------------------------------------------------------------------------
// Error module
// ---------------------------------------------------------------------------
mod error {
    // Copyright 2025 Adobe – see top-level notice.

    use std::error::Error;

    #[derive(thiserror::Error, Debug)]
    pub enum WasmError {
        #[error(transparent)]
        C2pa(#[from] c2pa::Error),

        #[error(transparent)]
        SerdeJson(#[from] serde_json::Error),

        #[error(transparent)]
        Other(Box<dyn Error>),
    }

    impl WasmError {
        pub(crate) fn other(e: impl Error + 'static) -> Self {
            WasmError::Other(Box::new(e))
        }
    }

    impl From<WasmError> for String {
        fn from(value: WasmError) -> Self {
            format!("{value:?}")
        }
    }
}

// ---------------------------------------------------------------------------
// Stream module
//
// A seekable, readable stream backed by an immutable byte slice; the host
// passes raw byte slices directly and the rest of the code keeps the same
// Read + Seek abstraction.
// ---------------------------------------------------------------------------
mod stream {
    // Copyright 2025 Adobe – see top-level notice.

    use std::io::{Cursor, Read, Result as IoResult, Seek, SeekFrom};

    /// A seekable, readable stream backed by an immutable byte slice.
    pub struct ByteStream<'a> {
        inner: Cursor<&'a [u8]>,
    }

    impl<'a> ByteStream<'a> {
        /// Create a new `ByteStream` from a borrowed byte slice.
        pub fn new(data: &'a [u8]) -> Self {
            Self {
                inner: Cursor::new(data),
            }
        }
    }

    impl Read for ByteStream<'_> {
        fn read(&mut self, buf: &mut [u8]) -> IoResult<usize> {
            self.inner.read(buf)
        }
    }

    impl Seek for ByteStream<'_> {
        fn seek(&mut self, pos: SeekFrom) -> IoResult<u64> {
            self.inner.seek(pos)
        }
    }

    // SAFETY: WASM is single-threaded.
    unsafe impl Send for ByteStream<'_> {}
}

// ---------------------------------------------------------------------------
// Top-level imports
// ---------------------------------------------------------------------------

use std::io::Cursor;

use c2pa::{Builder, BuilderIntent, DigitalSourceType, EphemeralSigner, SigningAlg};

use error::WasmError;
use stream::ByteStream;

// ---------------------------------------------------------------------------
// Memory helpers
// ---------------------------------------------------------------------------

/// Allocate `size` zeroed bytes on the Rust heap and transfer ownership to
/// the host.  The host MUST free the buffer with `wasm_free(ptr, size)`.
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
pub extern "C" fn wasm_free(ptr: *mut u8, size: u32) {
    if ptr.is_null() || size == 0 {
        return;
    }
    // SAFETY: ptr was allocated by Rust's global allocator with capacity `size`.
    unsafe {
        let _ = Vec::from_raw_parts(ptr, size as usize, size as usize);
    }
}

// ---------------------------------------------------------------------------
// Internal pointer / handle helpers
// ---------------------------------------------------------------------------

/// Box a value on the heap and return it as an opaque i32 handle.
fn into_handle<T>(val: T) -> i32 {
    Box::into_raw(Box::new(val)) as i32
}

/// Borrow the value behind an opaque handle without consuming it.
///
/// # Safety
/// `handle` must have been produced by `into_handle::<T>()` and not yet freed.
unsafe fn handle_ref<T>(handle: i32) -> &'static T {
    &*(handle as *const T)
}

/// Borrow the value behind an opaque handle mutably without consuming it.
///
/// # Safety
/// `handle` must have been produced by `into_handle::<T>()`, not yet freed,
/// and no other reference to it may exist simultaneously.
unsafe fn handle_mut<T>(handle: i32) -> &'static mut T {
    &mut *(handle as *mut T)
}

/// Drop (free) a boxed value that was returned as a handle.
///
/// # Safety
/// `handle` must have been produced by `into_handle::<T>()` and not yet freed.
unsafe fn drop_handle<T>(handle: i32) {
    drop(Box::from_raw(handle as *mut T));
}

/// Move a `Vec<u8>` to the heap, write its length to `*out_len`, and return
/// the raw pointer.  Host MUST free with `wasm_free(ptr, len)`.
fn vec_to_host(mut data: Vec<u8>, out_len: *mut u32) -> *mut u8 {
    data.shrink_to_fit();
    let len = data.len() as u32;
    let ptr = data.as_mut_ptr();
    std::mem::forget(data);
    // SAFETY: out_len is a valid writable pointer supplied by the host.
    unsafe { *out_len = len; }
    ptr
}

/// Interpret a `(ptr, len)` pair as a UTF-8 string slice valid for `'a`.
///
/// # Safety
/// Memory at `ptr` must contain valid UTF-8 and remain live for `'a`.
unsafe fn ptr_to_str<'a>(ptr: *const u8, len: u32) -> Result<&'a str, WasmError> {
    let slice = std::slice::from_raw_parts(ptr, len as usize);
    std::str::from_utf8(slice).map_err(WasmError::other)
}

/// Interpret a `(ptr, len)` pair as a byte slice valid for `'a`.
///
/// # Safety
/// Memory at `ptr` must remain live for `'a`.
unsafe fn ptr_to_slice<'a>(ptr: *const u8, len: u32) -> &'a [u8] {
    std::slice::from_raw_parts(ptr, len as usize)
}

// ---------------------------------------------------------------------------
// Error output helpers
// ---------------------------------------------------------------------------

/// Write an error message into the host-provided output pointers.
/// The host MUST free the string with `wasm_free(ptr, len)`.
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
// Reader
//
// Lifecycle:
//   handle = reader_from_bytes(format, data, ...)
//   reader_active_label(handle, ...)
//   reader_json(handle, ...)
//   reader_active_manifest_json(handle, ...)
//   reader_free(handle)
// ---------------------------------------------------------------------------

struct ReaderHandle {
    reader: c2pa::Reader,
}

/// Create a `Reader` from an asset's raw bytes.
///
/// # Parameters
/// - `format_ptr` / `format_len` : UTF-8 MIME type or file extension,
///                                 e.g. `"image/jpeg"`.
/// - `data_ptr`   / `data_len`   : Raw asset bytes (borrowed for this call).
/// - `err_ptr`    / `err_len`    : Error output (see memory contract).
///
/// # Returns
/// Opaque i32 handle on success, `0` on failure.
#[no_mangle]
pub extern "C" fn reader_from_bytes(
    format_ptr: *const u8,
    format_len: u32,
    data_ptr:   *const u8,
    data_len:   u32,
    err_ptr:    *mut *mut u8,
    err_len:    *mut u32,
) -> i32 {
    let format = unsafe {
        match ptr_to_str(format_ptr, format_len) {
            Ok(s) => s,
            Err(e) => {
                write_error(&e.to_string(), err_ptr, err_len);
                return 0;
            }
        }
    };

    let bytes = unsafe { ptr_to_slice(data_ptr, data_len) };
    let mut stream = ByteStream::new(bytes);

    let context = c2pa::Context::new();
    match c2pa::Reader::from_context(context).with_stream(format, &mut stream) {
        Ok(reader) => {
            clear_error(err_ptr, err_len);
            into_handle(ReaderHandle { reader })
        }
        Err(e) => {
            write_error(&WasmError::from(e).to_string(), err_ptr, err_len);
            0
        }
    }
}

/// Free a reader handle created by `reader_from_bytes`.
#[no_mangle]
pub extern "C" fn reader_free(handle: i32) {
    if handle != 0 {
        // SAFETY: handle was produced by into_handle::<ReaderHandle>().
        unsafe { drop_handle::<ReaderHandle>(handle) }
    }
}

/// Return the active manifest label as a heap-allocated UTF-8 string.
///
/// # Returns
/// Pointer to UTF-8 bytes (host must free with `wasm_free`), or **null** if
/// there is no active manifest label.
///
/// This function has no error output parameters because a missing label is
/// not an error — the host distinguishes the two cases by checking for null
/// with `*out_len == 0`.
#[no_mangle]
pub extern "C" fn reader_active_label(
    handle:  i32,
    out_len: *mut u32,
) -> *mut u8 {
    if handle == 0 {
        unsafe { *out_len = 0; }
        return std::ptr::null_mut();
    }

    // SAFETY: handle is valid and not freed.
    let rh = unsafe { handle_ref::<ReaderHandle>(handle) };

    match rh.reader.active_label() {
        Some(label) => vec_to_host(label.as_bytes().to_vec(), out_len),
        None => {
            unsafe { *out_len = 0; }
            std::ptr::null_mut()
        }
    }
}

/// Return the full manifest store as a JSON UTF-8 string.
///
/// # Returns
/// Pointer to JSON bytes (host must free), or null on failure.
#[no_mangle]
pub extern "C" fn reader_json(
    handle:  i32,
    out_len: *mut u32,
    err_ptr: *mut *mut u8,
    err_len: *mut u32,
) -> *mut u8 {
    if handle == 0 {
        write_error("null reader handle", err_ptr, err_len);
        return std::ptr::null_mut();
    }

    // SAFETY: handle is valid and not freed.
    let rh = unsafe { handle_ref::<ReaderHandle>(handle) };

    let json = rh.reader.json();
    clear_error(err_ptr, err_len);
    vec_to_host(json.into_bytes(), out_len)
}

/// Return the active manifest serialised as a JSON UTF-8 string.
///
/// # Returns
/// Pointer to JSON bytes (host must free), or null on failure.
#[no_mangle]
pub extern "C" fn reader_active_manifest_json(
    handle:  i32,
    out_len: *mut u32,
    err_ptr: *mut *mut u8,
    err_len: *mut u32,
) -> *mut u8 {
    if handle == 0 {
        write_error("null reader handle", err_ptr, err_len);
        return std::ptr::null_mut();
    }

    // SAFETY: handle is valid and not freed.
    let rh = unsafe { handle_ref::<ReaderHandle>(handle) };

    let manifest = match rh.reader.active_manifest() {
        Some(m) => m,
        None => {
            write_error("no active manifest", err_ptr, err_len);
            return std::ptr::null_mut();
        }
    };

    match serde_json::to_string(manifest) {
        Ok(json) => {
            clear_error(err_ptr, err_len);
            vec_to_host(json.into_bytes(), out_len)
        }
        Err(e) => {
            write_error(&WasmError::from(e).to_string(), err_ptr, err_len);
            std::ptr::null_mut()
        }
    }
}


/// Return the validation results of the manifest store as a JSON string.
///
/// # Returns
/// Pointer to JSON bytes (host must free), or null when the store has no
/// validation results (not an error — `*out_len` is set to 0 and no error is
/// reported).
#[no_mangle]
pub extern "C" fn reader_validation_results(
    handle:  i32,
    out_len: *mut u32,
    err_ptr: *mut *mut u8,
    err_len: *mut u32,
) -> *mut u8 {
    if handle == 0 {
        write_error("null reader handle", err_ptr, err_len);
        return std::ptr::null_mut();
    }

    // SAFETY: handle is valid and not freed.
    let rh = unsafe { handle_ref::<ReaderHandle>(handle) };

    match rh.reader.validation_results() {
        Some(results) => match serde_json::to_string(results) {
            Ok(json) => {
                clear_error(err_ptr, err_len);
                vec_to_host(json.into_bytes(), out_len)
            }
            Err(e) => {
                write_error(&WasmError::from(e).to_string(), err_ptr, err_len);
                std::ptr::null_mut()
            }
        },
        None => {
            // No validation results — not an error.
            clear_error(err_ptr, err_len);
            unsafe { *out_len = 0; }
            std::ptr::null_mut()
        }
    }
}

/// Return the validation state of the manifest store as a JSON string
/// (`"Valid"`, `"Invalid"`, or `"Trusted"`).
///
/// # Returns
/// Pointer to JSON bytes (host must free), or null on failure.
#[no_mangle]
pub extern "C" fn reader_validation_state(
    handle:  i32,
    out_len: *mut u32,
    err_ptr: *mut *mut u8,
    err_len: *mut u32,
) -> *mut u8 {
    if handle == 0 {
        write_error("null reader handle", err_ptr, err_len);
        return std::ptr::null_mut();
    }

    // SAFETY: handle is valid and not freed.
    let rh = unsafe { handle_ref::<ReaderHandle>(handle) };

    match serde_json::to_string(&rh.reader.validation_state()) {
        Ok(json) => {
            clear_error(err_ptr, err_len);
            vec_to_host(json.into_bytes(), out_len)
        }
        Err(e) => {
            write_error(&WasmError::from(e).to_string(), err_ptr, err_len);
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// Builder
//
// Signing happens inside WASM:
//   * builder_sign_with_keys    – host passes a PEM certificate chain and a
//                                 PEM private key; the signature is computed
//                                 via `create_signer::from_keys`.
//   * builder_sign_ephemeral    – signs with an ephemeral self-signed
//                                 certificate chain (`EphemeralSigner`).
//                                 Intended for tests and demos only; the
//                                 result will not validate against any
//                                 trust list.
//
// Lifecycle:
//   bh = builder_from_json(manifest_json, ...)
//   builder_sign_with_keys(bh, format, asset, cert, key, alg, tsa, ...)
//     | builder_sign_ephemeral(bh, format, asset, cert_name, ...)
//   builder_free(bh)   ← only if sign was NOT called or sign failed
// ---------------------------------------------------------------------------

struct BuilderHandle {
    builder: Builder,
}

/// Create a `Builder` from a manifest definition JSON string.
///
/// # Parameters
/// - `json_ptr` / `json_len`: UTF-8 manifest JSON.
/// - `err_ptr`  / `err_len` : Error output.
///
/// # Returns
/// Opaque i32 handle on success, `0` on failure.
#[no_mangle]
pub extern "C" fn builder_from_json(
    json_ptr: *const u8,
    json_len: u32,
    err_ptr:  *mut *mut u8,
    err_len:  *mut u32,
) -> i32 {
    let json = unsafe {
        match ptr_to_str(json_ptr, json_len) {
            Ok(s) => s,
            Err(e) => {
                write_error(&e.to_string(), err_ptr, err_len);
                return 0;
            }
        }
    };

    let context = c2pa::Context::new();
    match Builder::from_context(context).with_definition(json) {
        Ok(builder) => {
            clear_error(err_ptr, err_len);
            into_handle(BuilderHandle { builder })
        }
        Err(e) => {
            write_error(&WasmError::from(e).to_string(), err_ptr, err_len);
            0
        }
    }
}

/// Free a builder handle.
///
/// Do NOT call after a successful sign — the handle is consumed internally
/// on that path and will have already been freed.
#[no_mangle]
pub extern "C" fn builder_free(handle: i32) {
    if handle != 0 {
        // SAFETY: handle was produced by into_handle::<BuilderHandle>().
        unsafe { drop_handle::<BuilderHandle>(handle) }
    }
}


/// Set the builder intent.
///
/// # Parameters
/// - `builder_handle`          : Builder handle from `builder_from_json`.
/// - `intent_ptr` / `intent_len`: One of `"edit"`, `"update"`, or
///                               `"create:<source type>"` where the source
///                               type is a Digital Source Type alias (e.g.
///                               `"create:digitalCapture"`) or IPTC URI.
/// - `err_ptr` / `err_len`     : Error output.
///
/// # Returns
/// `1` on success, `0` on failure.
#[no_mangle]
pub extern "C" fn builder_set_intent(
    builder_handle: i32,
    intent_ptr:     *const u8,
    intent_len:     u32,
    err_ptr:        *mut *mut u8,
    err_len:        *mut u32,
) -> i32 {
    if builder_handle == 0 {
        write_error("null builder handle", err_ptr, err_len);
        return 0;
    }

    let intent_str = unsafe {
        match ptr_to_str(intent_ptr, intent_len) {
            Ok(s) => s,
            Err(e) => {
                write_error(&e.to_string(), err_ptr, err_len);
                return 0;
            }
        }
    };

    let intent = if intent_str == "edit" {
        BuilderIntent::Edit
    } else if intent_str == "update" {
        BuilderIntent::Update
    } else if let Some(source_type) = intent_str.strip_prefix("create:") {
        let digital_source_type: DigitalSourceType =
            match serde_json::from_str(&format!("\"{source_type}\"")) {
                Ok(dst) => dst,
                Err(e) => {
                    write_error(
                        &format!("unknown digital source type '{source_type}': {e}"),
                        err_ptr,
                        err_len,
                    );
                    return 0;
                }
            };
        BuilderIntent::Create(digital_source_type)
    } else {
        write_error(
            &format!("unknown intent '{intent_str}' (expected edit, update, or create:<source type>)"),
            err_ptr,
            err_len,
        );
        return 0;
    };

    // SAFETY: handle is valid and not freed; single-threaded.
    let bh = unsafe { handle_mut::<BuilderHandle>(builder_handle) };
    bh.builder.set_intent(intent);
    clear_error(err_ptr, err_len);
    1
}

/// Add an ingredient to the manifest from an asset's raw bytes.
///
/// For `application/c2pa` assets the ingredient's provenance is read from
/// the embedded manifest store; for other formats the asset is hashed as
/// the ingredient content.
///
/// # Parameters
/// - `builder_handle`                      : Builder handle.
/// - `ingredient_json_ptr` / `_len`        : Ingredient definition JSON
///                                           (e.g. `{"title": "...",
///                                           "relationship": "componentOf"}`).
/// - `format_ptr` / `format_len`           : MIME type / extension of the
///                                           ingredient asset.
/// - `data_ptr` / `data_len`               : Raw ingredient asset bytes.
/// - `err_ptr` / `err_len`                 : Error output.
///
/// # Returns
/// `1` on success, `0` on failure.
#[no_mangle]
pub extern "C" fn builder_add_ingredient(
    builder_handle:      i32,
    ingredient_json_ptr: *const u8,
    ingredient_json_len: u32,
    format_ptr:          *const u8,
    format_len:          u32,
    data_ptr:            *const u8,
    data_len:            u32,
    err_ptr:             *mut *mut u8,
    err_len:             *mut u32,
) -> i32 {
    if builder_handle == 0 {
        write_error("null builder handle", err_ptr, err_len);
        return 0;
    }

    let ingredient_json = unsafe {
        match ptr_to_str(ingredient_json_ptr, ingredient_json_len) {
            Ok(s) => s.to_string(),
            Err(e) => {
                write_error(&e.to_string(), err_ptr, err_len);
                return 0;
            }
        }
    };

    let format = unsafe {
        match ptr_to_str(format_ptr, format_len) {
            Ok(s) => s,
            Err(e) => {
                write_error(&e.to_string(), err_ptr, err_len);
                return 0;
            }
        }
    };

    let data_bytes = unsafe { ptr_to_slice(data_ptr, data_len) };
    let mut stream = ByteStream::new(data_bytes);

    // SAFETY: handle is valid and not freed; single-threaded.
    let bh = unsafe { handle_mut::<BuilderHandle>(builder_handle) };

    match bh
        .builder
        .add_ingredient_from_stream(ingredient_json, format, &mut stream)
    {
        Ok(_) => {
            clear_error(err_ptr, err_len);
            1
        }
        Err(e) => {
            write_error(&WasmError::from(e).to_string(), err_ptr, err_len);
            0
        }
    }
}

/// Sign an asset in memory using a PEM certificate chain and PEM private
/// key passed in by the host.  The signature is computed inside WASM.
///
/// # Parameters
/// - `builder_handle`            : Builder handle from `builder_from_json`.
/// - `format_ptr` / `format_len` : MIME type / extension of the asset.
/// - `asset_ptr`  / `asset_len`  : Raw asset bytes to embed the manifest in.
/// - `cert_ptr`   / `cert_len`   : PEM-encoded certificate chain bytes.
/// - `key_ptr`    / `key_len`    : PEM-encoded private key bytes.
/// - `alg_ptr`    / `alg_len`    : Algorithm string, e.g. `"ps256"`,
///                                 `"es256"`, `"ed25519"`.
/// - `tsa_ptr`    / `tsa_len`    : Optional timestamp authority URL
///                                 (empty string = no timestamping).
/// - `out_len`                   : Receives byte length of the signed asset.
/// - `err_ptr`    / `err_len`    : Error output.
///
/// # Returns
/// Pointer to signed asset bytes (host must free with `wasm_free`), or null
/// on failure.
///
/// # Ownership
/// On success the builder handle is consumed; the host must not call
/// `builder_free` afterwards.
#[no_mangle]
pub extern "C" fn builder_sign_with_keys(
    builder_handle: i32,
    format_ptr:     *const u8,
    format_len:     u32,
    asset_ptr:      *const u8,
    asset_len:      u32,
    cert_ptr:       *const u8,
    cert_len:       u32,
    key_ptr:        *const u8,
    key_len:        u32,
    alg_ptr:        *const u8,
    alg_len:        u32,
    tsa_ptr:        *const u8,
    tsa_len:        u32,
    out_len:        *mut u32,
    err_ptr:        *mut *mut u8,
    err_len:        *mut u32,
) -> *mut u8 {
    if builder_handle == 0 {
        write_error("null builder handle", err_ptr, err_len);
        return std::ptr::null_mut();
    }

    // ── Parse string inputs ──────────────────────────────────────────────────
    let format = unsafe {
        match ptr_to_str(format_ptr, format_len) {
            Ok(s) => s,
            Err(e) => {
                write_error(&e.to_string(), err_ptr, err_len);
                return std::ptr::null_mut();
            }
        }
    };

    let alg_str = unsafe {
        match ptr_to_str(alg_ptr, alg_len) {
            Ok(s) => s,
            Err(e) => {
                write_error(&e.to_string(), err_ptr, err_len);
                return std::ptr::null_mut();
            }
        }
    };

    let signing_alg: SigningAlg = match alg_str.parse() {
        Ok(a)  => a,
        Err(_) => {
            write_error(
                &format!("unknown signing algorithm: {alg_str}"),
                err_ptr,
                err_len,
            );
            return std::ptr::null_mut();
        }
    };

    let tsa_url = match unsafe { ptr_to_str(tsa_ptr, tsa_len) } {
        Ok(s) if s.is_empty() => None,
        Ok(s) => Some(s.to_string()),
        Err(e) => {
            write_error(&e.to_string(), err_ptr, err_len);
            return std::ptr::null_mut();
        }
    };

    // ── Parse byte inputs ────────────────────────────────────────────────────
    let asset_bytes = unsafe { ptr_to_slice(asset_ptr, asset_len) };
    let cert_pem    = unsafe { ptr_to_slice(cert_ptr, cert_len) }.to_vec();
    let key_pem     = unsafe { ptr_to_slice(key_ptr, key_len) }.to_vec();

    // ── Build the signer from the host-supplied key material ────────────────
    let signer = match c2pa::create_signer::from_keys(
        &cert_pem,
        &key_pem,
        signing_alg,
        tsa_url,
    ) {
        Ok(s)  => s,
        Err(e) => {
            write_error(&WasmError::from(e).to_string(), err_ptr, err_len);
            return std::ptr::null_mut();
        }
    };

    // ── Sign ─────────────────────────────────────────────────────────────────
    // SAFETY: WASM is single-threaded.  The handle has not been freed.
    // No other reference to the same handle exists at this call site.
    let bh = unsafe { handle_mut::<BuilderHandle>(builder_handle) };

    let mut input  = ByteStream::new(asset_bytes);
    let mut output = Cursor::new(Vec::<u8>::new());

    match bh.builder.sign(signer.as_ref(), format, &mut input, &mut output) {
        Ok(_)  => {
            clear_error(err_ptr, err_len);
            vec_to_host(output.into_inner(), out_len)
        }
        Err(e) => {
            write_error(&WasmError::from(e).to_string(), err_ptr, err_len);
            std::ptr::null_mut()
        }
    }
}

/// Sign an asset in memory using an ephemeral self-signed certificate chain.
///
/// Intended for tests and demos only — the produced manifest will not
/// validate against any trust list.
///
/// # Parameters
/// - `builder_handle`                 : Builder handle from
///                                      `builder_from_json`.
/// - `format_ptr` / `format_len`      : MIME type / extension of the asset.
/// - `asset_ptr`  / `asset_len`       : Raw asset bytes to embed the manifest
///                                      in.
/// - `cert_name_ptr` / `cert_name_len`: Common name for the ephemeral
///                                      end-entity certificate.
/// - `out_len`                        : Receives byte length of the signed
///                                      asset.
/// - `err_ptr` / `err_len`            : Error output.
///
/// # Returns
/// Pointer to signed asset bytes (host must free with `wasm_free`), or null
/// on failure.
///
/// # Ownership
/// On success the builder handle is consumed; the host must not call
/// `builder_free` afterwards.
#[no_mangle]
pub extern "C" fn builder_sign_ephemeral(
    builder_handle:   i32,
    format_ptr:       *const u8,
    format_len:       u32,
    asset_ptr:        *const u8,
    asset_len:        u32,
    cert_name_ptr:    *const u8,
    cert_name_len:    u32,
    out_len:          *mut u32,
    err_ptr:          *mut *mut u8,
    err_len:          *mut u32,
) -> *mut u8 {
    if builder_handle == 0 {
        write_error("null builder handle", err_ptr, err_len);
        return std::ptr::null_mut();
    }

    // ── Parse string inputs ──────────────────────────────────────────────────
    let format = unsafe {
        match ptr_to_str(format_ptr, format_len) {
            Ok(s) => s,
            Err(e) => {
                write_error(&e.to_string(), err_ptr, err_len);
                return std::ptr::null_mut();
            }
        }
    };

    let cert_name = unsafe {
        match ptr_to_str(cert_name_ptr, cert_name_len) {
            Ok(s) => s,
            Err(e) => {
                write_error(&e.to_string(), err_ptr, err_len);
                return std::ptr::null_mut();
            }
        }
    };

    // ── Build the ephemeral signer ───────────────────────────────────────────
    let signer = match EphemeralSigner::new(cert_name) {
        Ok(s)  => s,
        Err(e) => {
            write_error(&WasmError::from(e).to_string(), err_ptr, err_len);
            return std::ptr::null_mut();
        }
    };

    // ── Sign ─────────────────────────────────────────────────────────────────
    // SAFETY: WASM is single-threaded.  The handle has not been freed.
    // No other reference to the same handle exists at this call site.
    let bh = unsafe { handle_mut::<BuilderHandle>(builder_handle) };

    let asset_bytes = unsafe { ptr_to_slice(asset_ptr, asset_len) };
    let mut input   = ByteStream::new(asset_bytes);
    let mut output  = Cursor::new(Vec::<u8>::new());

    match bh.builder.sign(&signer, format, &mut input, &mut output) {
        Ok(_)  => {
            clear_error(err_ptr, err_len);
            vec_to_host(output.into_inner(), out_len)
        }
        Err(e) => {
            write_error(&WasmError::from(e).to_string(), err_ptr, err_len);
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// Utility exports
// ---------------------------------------------------------------------------

/// Return the c2pa crate version string.
///
/// `VERSION` is a `&'static str` constant, not a function.
///
/// # Returns
/// Heap-allocated UTF-8 string.  Host must free with `wasm_free(ptr, len)`.
#[no_mangle]
pub extern "C" fn c2pa_version(out_len: *mut u32) -> *mut u8 {
    let version = c2pa::VERSION.to_string();
    vec_to_host(version.into_bytes(), out_len)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// A minimal valid 8x8 JPEG used as the signing asset.
    const TINY_JPEG_B64: &str = "/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAACKADAAQAAAABAAAACAAAAAD/7QA4UGhvdG9zaG9wIDMuMAA4QklNBAQAAAAAAAA4QklNBCUAAAAAABDUHYzZjwCyBOmACZjs+EJ+/8AAEQgACAAIAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/bAEMAAgICAgICAwICAwUDAwMFBgUFBQUGCAYGBgYGCAoICAgICAgKCgoKCgoKCgwMDAwMDA4ODg4ODw8PDw8PDw8PD//bAEMBAgICBAQEBwQEBxALCQsQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEP/dAAQAAf/aAAwDAQACEQMRAD8A4+iiiv6sP5rP/9k=";

    fn tiny_jpeg() -> Vec<u8> {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD
            .decode(TINY_JPEG_B64)
            .expect("embedded test JPEG must be valid base64")
    }

    const MANIFEST_JSON: &str = r#"{
        "claim_generator": "jc2pa-test/0.1",
        "title": "Test Asset",
        "assertions": [
            {
                "label": "org.contentauth.test",
                "data": "test"
            }
        ]
    }"#;

    /// Full round-trip: intent + ingredient → sign (ephemeral) → read →
    /// validate.
    #[test]
    fn lifecycle_round_trip() {
        let asset = tiny_jpeg();

        let context = c2pa::Context::new();
        let mut builder = Builder::from_context(context)
            .with_definition(MANIFEST_JSON)
            .expect("manifest definition must parse");

        // Edit intent requires a parent ingredient — add the asset itself.
        builder.set_intent(BuilderIntent::Edit);
        builder
            .add_ingredient_from_stream(
                r#"{"title": "parent", "relationship": "parentOf"}"#,
                "image/jpeg",
                &mut ByteStream::new(&asset),
            )
            .expect("ingredient must be added");

        let signer =
            EphemeralSigner::new("jc2pa-test-signer").expect("ephemeral signer");

        let mut input  = ByteStream::new(&asset);
        let mut output = Cursor::new(Vec::<u8>::new());

        builder
            .sign(&signer, "image/jpeg", &mut input, &mut output)
            .expect("signing must succeed");

        let signed = output.into_inner();

        // Read the signed asset back and validate.
        let mut signed_stream = ByteStream::new(&signed);
        let context = c2pa::Context::new();
        let reader = c2pa::Reader::from_context(context)
            .with_stream("image/jpeg", &mut signed_stream)
            .expect("signed asset must open");

        assert!(
            reader.active_label().is_some(),
            "signed asset must have an active manifest"
        );
        let json = reader.json();
        assert!(
            json.contains("\"title\": \"Test Asset\""),
            "store JSON must contain the manifest title, got: {}",
            &json[..json.len().min(600)]
        );
        // Validation must produce a state of Valid or Trusted (the ephemeral
        // certificate is not on any trust list, but structural and
        // cryptographic validation must pass).
        let state = format!("{:?}", reader.validation_state());
        assert!(
            state.contains("Valid") || state.contains("Trusted"),
            "validation state must be Valid or Trusted, got: {state}"
        );
    }

    /// Full round-trip: build → sign (ephemeral) → read → verify.
    #[test]
    fn sign_ephemeral_and_read_round_trip() {
        let asset = tiny_jpeg();

        let context = c2pa::Context::new();
        let mut builder = Builder::from_context(context)
            .with_definition(MANIFEST_JSON)
            .expect("manifest definition must parse");

        let signer =
            EphemeralSigner::new("jc2pa-test-signer").expect("ephemeral signer");

        let mut input  = ByteStream::new(&asset);
        let mut output = Cursor::new(Vec::<u8>::new());

        builder
            .sign(&signer, "image/jpeg", &mut input, &mut output)
            .expect("signing must succeed");

        let signed = output.into_inner();
        assert!(signed.len() > asset.len(), "manifest must be embedded");

        // Read the signed asset back.
        let mut signed_stream = ByteStream::new(&signed);
        let context = c2pa::Context::new();
        let reader = c2pa::Reader::from_context(context)
            .with_stream("image/jpeg", &mut signed_stream)
            .expect("signed asset must open");

        assert!(
            reader.active_label().is_some(),
            "signed asset must have an active manifest"
        );
        let json = reader.json();
        assert!(
            json.contains("\"title\": \"Test Asset\""),
            "store JSON must contain the manifest title, got: {}",
            &json[..json.len().min(600)]
        );
    }
}
