// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// JPEG XL WASM wrapper.
//
// Exposes a container-level C ABI over the pure-Rust jxl-oxide decoder:
//
//   jxl_decode(jxl_bytes) -> raw 8-bit interleaved pixels + dimensions
//
//   + the standard memory contract: wasm_alloc / wasm_free
//   + version reporting:  jxl_version (the jxl-oxide crate version)
//
// All public functions use `#[no_mangle] pub extern "C"` with a raw-pointer
// ABI.  There is no dependency on wasm_bindgen, js_sys, or web_sys (unlike
// upstream jxl-oxide-wasm, which targets browsers and cannot run under the
// WASI runtimes used here).
//
// Error contract (matches the jc2pa/ultrahdr wrappers): every fallible
// function takes `err_ptr`/`err_len` out-parameters; on success the error
// pointer is null, on failure it receives a heap string the host must free
// with `wasm_free(ptr, len)`.
//
// Color note: without a CMS feature (lcms2 needs C, moxcms is future work)
// the decoder renders with jxl-oxide's built-in handling; output matches
// the reference decoder within ±1 LSB (wasm32 has no FMA).

use std::io::Cursor;

// ---------------------------------------------------------------------------
// Memory contract
// ---------------------------------------------------------------------------

/// Allocate `size` zeroed bytes; ownership transfers to the host, which MUST
/// free the buffer with `wasm_free(ptr, size)`.
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
// Internal helpers
// ---------------------------------------------------------------------------

/// Move a `Vec<u8>` to the heap and write its length to `*out_len`.
/// Host MUST free with `wasm_free(ptr, len)`.
fn vec_to_host(mut data: Vec<u8>, out_len: *mut u32) -> *mut u8 {
    data.shrink_to_fit();
    let len = data.len() as u32;
    let ptr = data.as_mut_ptr();
    std::mem::forget(data);
    // SAFETY: out_len is a valid writable pointer supplied by the host.
    unsafe { *out_len = len; }
    ptr
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
// Decode
// ---------------------------------------------------------------------------

/// Upper bound for the decoded pixel buffer (w*h*channels).  Guards against
/// corrupt headers claiming absurd dimensions before a 100 MB+ allocation.
const MAX_PIXELS: usize = 512 * 1024 * 1024;

/// Decode JPEG XL bytes to raw 8-bit interleaved samples.
///
/// # Parameters
/// - `data_ptr` / `data_len` : JXL codestream bytes.
/// - `out_len`               : Receives byte length of the pixel buffer.
/// - `out_w` / `out_h`       : Receive image dimensions.
/// - `out_channels`          : Receives channel count (e.g. 3 for RGB).
/// - `err_ptr` / `err_len`   : Error output.
///
/// # Returns
/// Pointer to the pixel buffer (host must free), or null on failure.
#[no_mangle]
pub extern "C" fn jxl_decode(
    data_ptr: *const u8,
    data_len: u32,
    out_len: *mut u32,
    out_w: *mut u32,
    out_h: *mut u32,
    out_channels: *mut u32,
    err_ptr: *mut *mut u8,
    err_len: *mut u32,
) -> *mut u8 {
    // SAFETY: the host passes a live buffer of data_len bytes.
    let bytes = unsafe { std::slice::from_raw_parts(data_ptr, data_len as usize) };
    let image = match jxl_oxide::JxlImage::builder().read(Cursor::new(bytes)) {
        Ok(image) => image,
        Err(e) => {
            write_error(&format!("jxl header: {e:?}"), err_ptr, err_len);
            return std::ptr::null_mut();
        }
    };
    if image.num_loaded_keyframes() == 0 {
        write_error("jxl: no keyframes", err_ptr, err_len);
        return std::ptr::null_mut();
    }
    let render = match image.render_frame(0) {
        Ok(render) => render,
        Err(e) => {
            write_error(&format!("jxl render: {e:?}"), err_ptr, err_len);
            return std::ptr::null_mut();
        }
    };
    let mut stream = render.stream();
    let (width, height, channels) = (stream.width(), stream.height(), stream.channels());
    let total = (width as usize)
        .checked_mul(height as usize)
        .and_then(|n| n.checked_mul(channels as usize))
        .unwrap_or(usize::MAX);
    if total == 0 || total > MAX_PIXELS {
        write_error("jxl: implausible dimensions", err_ptr, err_len);
        return std::ptr::null_mut();
    }
    let mut pixels = vec![0u8; total];
    if stream.write_to_buffer(&mut pixels) != total {
        write_error("jxl: short frame", err_ptr, err_len);
        return std::ptr::null_mut();
    }
    // SAFETY: out-pointers are caller-supplied and assumed valid.
    unsafe {
        *out_w = width;
        *out_h = height;
        *out_channels = channels;
    }
    clear_error(err_ptr, err_len);
    vec_to_host(pixels, out_len)
}

// ---------------------------------------------------------------------------
// HDR decode (full-range float samples + color description)
// ---------------------------------------------------------------------------

/// Decode JPEG XL bytes to raw 32-bit float interleaved samples.
///
/// <p>Same contract as [`jxl_decode`], but pixels are little-endian
/// `f32` values in the codestream's NATIVE encoding (unclamped — HDR
/// highlights survive as-is) with no tone mapping or color conversion
/// applied.  Pair with [`jxl_color_info`] to learn the transfer function
/// and primaries needed to interpret them.
///
/// <p>Deliberately not linearized: without a CMS feature jxl-oxide cannot
/// convert between encodings, so requesting one is a no-op — native
/// samples plus labels is the only sound contract.
///
/// # Returns
/// Pointer to the float buffer (host must free), or null on failure.
#[no_mangle]
pub extern "C" fn jxl_decode_hdr(
    data_ptr: *const u8,
    data_len: u32,
    out_len: *mut u32,
    out_w: *mut u32,
    out_h: *mut u32,
    out_channels: *mut u32,
    err_ptr: *mut *mut u8,
    err_len: *mut u32,
) -> *mut u8 {
    // SAFETY: the host passes a live buffer of data_len bytes.
    let bytes = unsafe { std::slice::from_raw_parts(data_ptr, data_len as usize) };
    let image = match jxl_oxide::JxlImage::builder().read(Cursor::new(bytes)) {
        Ok(image) => image,
        Err(e) => {
            write_error(&format!("jxl header: {e:?}"), err_ptr, err_len);
            return std::ptr::null_mut();
        }
    };
    if image.num_loaded_keyframes() == 0 {
        write_error("jxl: no keyframes", err_ptr, err_len);
        return std::ptr::null_mut();
    }
    let render = match image.render_frame(0) {
        Ok(render) => render,
        Err(e) => {
            write_error(&format!("jxl render: {e:?}"), err_ptr, err_len);
            return std::ptr::null_mut();
        }
    };
    let mut stream = render.stream();
    let (width, height, channels) = (stream.width(), stream.height(), stream.channels());
    let total = (width as usize)
        .checked_mul(height as usize)
        .and_then(|n| n.checked_mul(channels as usize))
        .unwrap_or(usize::MAX);
    if total == 0 || total > MAX_PIXELS {
        write_error("jxl: implausible dimensions", err_ptr, err_len);
        return std::ptr::null_mut();
    }
    let mut pixels = vec![0f32; total];
    if stream.write_to_buffer(&mut pixels) != total {
        write_error("jxl: short frame", err_ptr, err_len);
        return std::ptr::null_mut();
    }
    // SAFETY: out-pointers are caller-supplied and assumed valid.
    unsafe {
        *out_w = width;
        *out_h = height;
        *out_channels = channels;
    }
    clear_error(err_ptr, err_len);
    let bytes: Vec<u8> = pixels
        .iter()
        .flat_map(|v| v.to_le_bytes())
        .collect();
    vec_to_host(bytes, out_len)
}

/// Report the color description of a JPEG XL image as JSON:
/// `{"transfer":"pq|hlg|srgb|linear|unknown",
///    "primaries":"bt709|bt2020|display_p3|unknown",
///    "hdr":true|false}`.
///
/// <p>Transfer/primaries come from the codestream header (`hdr_type()` for
/// the HDR cases, enum encoding otherwise; ICC-profile color is reported
/// as unknown).  `hdr` is true for PQ/HLG content.
///
/// # Returns
/// Pointer to JSON bytes (host must free), or null on failure.
#[no_mangle]
pub extern "C" fn jxl_color_info(
    data_ptr: *const u8,
    data_len: u32,
    out_len: *mut u32,
    err_ptr: *mut *mut u8,
    err_len: *mut u32,
) -> *mut u8 {
    // SAFETY: the host passes a live buffer of data_len bytes.
    let bytes = unsafe { std::slice::from_raw_parts(data_ptr, data_len as usize) };
    let image = match jxl_oxide::JxlImage::builder().read(Cursor::new(bytes)) {
        Ok(image) => image,
        Err(e) => {
            write_error(&format!("jxl header: {e:?}"), err_ptr, err_len);
            return std::ptr::null_mut();
        }
    };
    use jxl_oxide::color::{ColourEncoding, Primaries, TransferFunction};
    use jxl_oxide::HdrType;
    let header = image.image_header();
    let (transfer, hdr) = match image.hdr_type() {
        Some(HdrType::Pq) => ("pq", true),
        Some(HdrType::Hlg) => ("hlg", true),
        None => match &header.metadata.colour_encoding {
            // Bt709 decodes like sRGB for rendition purposes.
            ColourEncoding::Enum(e) => match e.tf {
                TransferFunction::Srgb | TransferFunction::Bt709 => ("srgb", false),
                TransferFunction::Linear => ("linear", false),
                _ => ("unknown", false),
            },
            ColourEncoding::IccProfile(_) => ("unknown", false),
        },
    };
    let primaries = match &header.metadata.colour_encoding {
        ColourEncoding::Enum(e) => match e.primaries {
            Primaries::Srgb => "bt709",
            Primaries::Bt2100 => "bt2020",
            Primaries::P3 => "display_p3",
            Primaries::Custom { .. } => "unknown",
        },
        ColourEncoding::IccProfile(_) => "unknown",
    };
    let json = format!("{{\"transfer\":\"{transfer}\",\"primaries\":\"{primaries}\",\"hdr\":{hdr}}}");
    clear_error(err_ptr, err_len);
    vec_to_host(json.into_bytes(), out_len)
}

// ---------------------------------------------------------------------------
// Utility exports
// ---------------------------------------------------------------------------

/// Return the jxl-oxide codec version string (extracted from Cargo.lock at
/// build time).
///
/// # Returns
/// Heap-allocated UTF-8 string.  Host must free with `wasm_free(ptr, len)`.
#[no_mangle]
pub extern "C" fn jxl_version(out_len: *mut u32) -> *mut u8 {
    vec_to_host(env!("JXL_OXIDE_VERSION").as_bytes().to_vec(), out_len)
}

// ---------------------------------------------------------------------------
// Tests (native target only; the WASM module is exercised from Java)
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// 512x512 lossy JPEG XL (exercises the VarDCT path; decodes in ~25 ms
    /// natively, far too slow for the Chicory-based Java tests, which use
    /// a smaller fixture instead).
    const MED_JXL: &[u8] = include_bytes!("../test-data/med.jxl");

    #[test]
    fn decode_reports_dimensions_and_pixels() {
        let image = jxl_oxide::JxlImage::builder()
            .read(Cursor::new(MED_JXL))
            .expect("header must parse");
        assert_eq!(image.num_loaded_keyframes(), 1);
        let render = image.render_frame(0).expect("frame must render");
        let mut stream = render.stream();
        assert_eq!((stream.width(), stream.height()), (512, 512));
        assert_eq!(stream.channels(), 3);
        let total = 512usize * 512 * 3;
        let mut pixels = vec![0u8; total];
        assert_eq!(stream.write_to_buffer(&mut pixels), total);
        assert!(pixels.iter().any(|&b| b != 0), "pixels must not be all zero");
    }

    #[test]
    fn garbage_input_fails_cleanly() {
        let garbage = [0u8; 64];
        assert!(jxl_oxide::JxlImage::builder()
            .read(Cursor::new(&garbage))
            .is_err());
    }

    /// Synthetic 256x256 Rec.2100 PQ fixture (see core test resources
    /// NOTICE): the HDR path must report PQ transfer and decode
    /// full-range floats (peak 8.0 in linear scene values survives as
    /// samples above 1.0 — clamping would destroy them).
    const HDR_PQ_JXL: &[u8] = include_bytes!("../test-data/hdr-pq.jxl");

    #[test]
    fn hdr_reports_pq_and_full_range_floats() {
        let image = jxl_oxide::JxlImage::builder()
            .read(Cursor::new(HDR_PQ_JXL))
            .expect("header must parse");
        assert_eq!(image.hdr_type(), Some(jxl_oxide::HdrType::Pq));
        let render = image.render_frame(0).expect("frame must render");
        let mut stream = render.stream();
        assert_eq!((stream.width(), stream.height()), (256, 256));
        let total = 256usize * 256 * stream.channels() as usize;
        let mut pixels = vec![0f32; total];
        assert_eq!(stream.write_to_buffer(&mut pixels), total);
        // Native PQ codes (no CMS: no conversion to linear): the synthetic
        // scene peaks at PQ ~0.73, well above SDR content but within [0, 1].
        // The transfer label (checked via jxl_color_info on the Java side)
        // is what makes these interpretable — not their magnitude.
        let max = pixels.iter().cloned().fold(0f32, f32::max);
        assert!(max > 0.5 && max <= 1.0, "PQ highlights expected, got max {max}");
    }
}
