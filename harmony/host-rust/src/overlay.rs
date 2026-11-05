// SPDX-License-Identifier: MIT
//! The boot overlay: one gzip member around a ustar of `home/...` and
//! `workspace/...`, rebuilt from the materialized tree on every request.
//!
//! This mirrors the Android `BootOverlayPacker`: only the two VFS roots are
//! carried, names are sorted, mtime/uid/gid are zero for determinism, symlinks
//! and special files are refused (the Worker VFS has no model for them), and a
//! tree above the caps fails instead of being truncated.

use std::fs::File;
use std::io::{Read, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

use flate2::Compression;

const TAR_BLOCK: usize = 512;
const MAX_ARCHIVE_BYTES: u64 = 256 * 1024 * 1024;
const MAX_ENTRIES: usize = 200_000;
const ROOTS: [&str; 2] = ["home", "workspace"];

/// Packs `materialized/{home,workspace}` as `gzip(ustar(entries))`.
pub fn pack(materialized: &Path) -> Result<Vec<u8>, String> {
    let mut archive = Vec::new();
    let mut entries = 0usize;
    for root_name in ROOTS {
        let root = materialized.join(root_name);
        match std::fs::symlink_metadata(&root) {
            Ok(metadata) => {
                if metadata.file_type().is_symlink() || !metadata.is_dir() {
                    return Err(format!("overlay root {root_name} is not a plain directory"));
                }
                push_entry(
                    &mut archive,
                    &mut entries,
                    &format!("{root_name}/"),
                    non_zero_mode(metadata.permissions().mode(), 0o755),
                    None,
                )?;
                walk(&root, root_name, &mut archive, &mut entries)?;
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => {
                return Err(format!(
                    "cannot stat overlay root {}: {error}",
                    root.display()
                ));
            }
        }
    }
    archive.extend_from_slice(&[0u8; TAR_BLOCK * 2]);
    if archive.len() as u64 > MAX_ARCHIVE_BYTES {
        return Err(format!(
            "overlay is {} bytes, above the {MAX_ARCHIVE_BYTES} byte cap",
            archive.len()
        ));
    }
    let mut encoder = flate2::GzBuilder::new()
        .mtime(0)
        .write(Vec::new(), Compression::default());
    encoder
        .write_all(&archive)
        .map_err(|error| format!("cannot gzip overlay: {error}"))?;
    encoder
        .finish()
        .map_err(|error| format!("cannot finish gzip overlay: {error}"))
}

fn walk(
    directory: &Path,
    prefix: &str,
    archive: &mut Vec<u8>,
    entries: &mut usize,
) -> Result<(), String> {
    let mut names = Vec::new();
    for entry in std::fs::read_dir(directory)
        .map_err(|error| format!("cannot list {}: {error}", directory.display()))?
    {
        let entry =
            entry.map_err(|error| format!("cannot list {}: {error}", directory.display()))?;
        let name = entry.file_name().into_string().map_err(|_| {
            format!(
                "overlay cannot carry a non-UTF-8 file name under {}",
                directory.display()
            )
        })?;
        names.push(name);
    }
    names.sort();
    for name in names {
        let path = directory.join(&name);
        let entry_name = format!("{prefix}/{name}");
        let metadata = std::fs::symlink_metadata(&path)
            .map_err(|error| format!("cannot stat {}: {error}", path.display()))?;
        if metadata.file_type().is_symlink() {
            return Err(format!(
                "overlay cannot carry symlink {entry_name}: the Worker VFS has no symlink model"
            ));
        }
        if metadata.is_dir() {
            push_entry(
                archive,
                entries,
                &format!("{entry_name}/"),
                non_zero_mode(metadata.permissions().mode(), 0o755),
                None,
            )?;
            walk(&path, &entry_name, archive, entries)?;
        } else if metadata.is_file() {
            let size = metadata.len();
            if size > MAX_ARCHIVE_BYTES {
                return Err(format!("overlay file {entry_name} exceeds the archive cap"));
            }
            let mut file =
                File::open(&path).map_err(|error| format!("cannot read {entry_name}: {error}"))?;
            let mut data = Vec::with_capacity(size as usize);
            file.read_to_end(&mut data)
                .map_err(|error| format!("cannot read {entry_name}: {error}"))?;
            if data.len() as u64 != size {
                return Err(format!("overlay file {entry_name} changed while packed"));
            }
            push_entry(
                archive,
                entries,
                &entry_name,
                non_zero_mode(metadata.permissions().mode(), 0o644),
                Some(&data),
            )?;
        } else {
            return Err(format!(
                "overlay cannot carry special file {entry_name}: only files and directories"
            ));
        }
    }
    Ok(())
}

fn push_entry(
    archive: &mut Vec<u8>,
    entries: &mut usize,
    name: &str,
    mode: u32,
    data: Option<&[u8]>,
) -> Result<(), String> {
    if *entries >= MAX_ENTRIES {
        return Err(format!(
            "overlay would exceed {MAX_ENTRIES} entries at {name}; it is not truncated"
        ));
    }
    let data = data.unwrap_or(&[]);
    let needed = archive.len() as u64 + TAR_BLOCK as u64 + data.len() as u64 + TAR_BLOCK as u64;
    if needed > MAX_ARCHIVE_BYTES {
        return Err(format!(
            "overlay would exceed {MAX_ARCHIVE_BYTES} bytes at {name}; it is not truncated"
        ));
    }
    let header = header(
        name,
        mode,
        data.len() as u64,
        data.is_empty() && name.ends_with('/'),
    )?;
    archive.extend_from_slice(&header);
    archive.extend_from_slice(data);
    let remainder = data.len() % TAR_BLOCK;
    if remainder != 0 {
        archive.extend_from_slice(&vec![0u8; TAR_BLOCK - remainder]);
    }
    *entries += 1;
    Ok(())
}

fn header(name: &str, mode: u32, size: u64, directory: bool) -> Result<[u8; TAR_BLOCK], String> {
    let (prefix, leaf) = split_name(name)?;
    let mut header = [0u8; TAR_BLOCK];
    write_field(&mut header, 0, 100, leaf.as_bytes());
    write_octal(&mut header, 100, 8, (mode & 0o7777) as u64);
    write_octal(&mut header, 108, 8, 0);
    write_octal(&mut header, 116, 8, 0);
    write_octal(&mut header, 124, 12, size);
    write_octal(&mut header, 136, 12, 0);
    for byte in &mut header[148..156] {
        *byte = b' ';
    }
    header[156] = if directory { b'5' } else { b'0' };
    write_field(&mut header, 257, 6, b"ustar\0");
    write_field(&mut header, 263, 2, b"00");
    if let Some(prefix) = prefix {
        write_field(&mut header, 345, 155, prefix.as_bytes());
    }
    let checksum: u64 = header.iter().map(|byte| *byte as u64).sum();
    let checksum = format!("{checksum:06o}");
    header[148..154].copy_from_slice(checksum.as_bytes());
    header[154] = 0;
    header[155] = b' ';
    Ok(header)
}

fn non_zero_mode(mode: u32, fallback: u32) -> u32 {
    let mode = mode & 0o7777;
    if mode == 0 {
        fallback
    } else {
        mode
    }
}

fn split_name(name: &str) -> Result<(Option<String>, String), String> {
    let bytes = name.as_bytes();
    if bytes.len() <= 100 {
        return Ok((None, name.to_owned()));
    }
    for index in (1..bytes.len()).rev() {
        if bytes[index - 1] != b'/' {
            continue;
        }
        let prefix_len = index - 1;
        let name_len = bytes.len() - index;
        if (1..=155).contains(&prefix_len) && name_len <= 100 {
            let prefix = std::str::from_utf8(&bytes[..prefix_len])
                .map_err(|_| "overlay name is not UTF-8".to_owned())?;
            let leaf = std::str::from_utf8(&bytes[index..])
                .map_err(|_| "overlay name is not UTF-8".to_owned())?;
            return Ok((Some(prefix.to_owned()), leaf.to_owned()));
        }
    }
    Err(format!(
        "overlay entry name {name} does not fit the ustar name+prefix split"
    ))
}

fn write_field(header: &mut [u8], offset: usize, length: usize, value: &[u8]) {
    let count = value.len().min(length);
    header[offset..offset + count].copy_from_slice(&value[..count]);
}

fn write_octal(header: &mut [u8], offset: usize, length: usize, value: u64) {
    let digits = format!("{value:o}");
    let padded = if digits.len() + 1 >= length {
        digits[digits.len() - (length - 1)..].to_owned()
    } else {
        format!("{}{}", "0".repeat(length - 1 - digits.len()), digits)
    };
    header[offset..offset + length - 1].copy_from_slice(padded.as_bytes());
    header[offset + length - 1] = 0;
}

/// Only used by tests to keep a temp path alive through the walk.
#[allow(dead_code)]
fn _path_marker(path: PathBuf) -> PathBuf {
    path
}
