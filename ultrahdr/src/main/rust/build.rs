// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke

// Extracts the resolved ultrahdr-rs version from Cargo.lock and exposes it
// to the crate as the UHDR_RS_VERSION environment variable.
use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let lock = fs::read_to_string(manifest_dir.join("../../Cargo.lock"))
        .or_else(|_| fs::read_to_string(manifest_dir.join("Cargo.lock")))
        .expect("Cargo.lock not found");

    let mut version = None;
    let mut current = String::new();
    for line in lock.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('[') {
            current.clear();
            continue;
        }
        if let Some(name) = trimmed.strip_prefix("name = ") {
            current = name.trim_matches('"').to_string();
        }
        if current == "ultrahdr-rs" {
            if let Some(v) = trimmed.strip_prefix("version = ") {
                version = Some(v.trim_matches('"').to_string());
                break;
            }
        }
    }

    let version = version.unwrap_or_else(|| "unknown".to_string());
    println!("cargo:rustc-env=UHDR_RS_VERSION={version}");
    println!("cargo:rerun-if-changed=Cargo.lock");
    println!("cargo:rerun-if-changed=build.rs");
}
