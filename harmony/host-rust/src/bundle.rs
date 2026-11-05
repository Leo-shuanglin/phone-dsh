// SPDX-License-Identifier: MIT
//! Safe installation of a worker bundle zip into the assets directory.
//!
//! Entry names are validated as relative, slash-separated paths with no dot
//! components; symlink entries are refused; extraction goes to a sibling
//! staging directory and replaces the assets directory only after every entry
//! has been validated and written.

use std::collections::HashSet;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Component, Path, PathBuf};

const MAX_ENTRIES: usize = 100_000;
const MAX_TOTAL_BYTES: u64 = 2 * 1024 * 1024 * 1024;
const MAX_ENTRY_BYTES: u64 = 512 * 1024 * 1024;

/// Installs `zip_path` as `assets_dir`, replacing an existing tree.
pub fn install_bundle(zip_path: &Path, assets_dir: &Path) -> Result<(), String> {
    let file = File::open(zip_path)
        .map_err(|error| format!("cannot open bundle {}: {error}", zip_path.display()))?;
    let mut archive = zip::ZipArchive::new(file)
        .map_err(|error| format!("bundle is not a zip archive: {error}"))?;
    if archive.len() > MAX_ENTRIES {
        return Err(format!(
            "bundle has {} entries, above the {MAX_ENTRIES} cap",
            archive.len()
        ));
    }

    // Pass 1: validate every name and declared size before writing anything.
    let mut seen = HashSet::new();
    let mut total = 0u64;
    for index in 0..archive.len() {
        let entry = archive
            .by_index(index)
            .map_err(|error| format!("cannot read bundle entry {index}: {error}"))?;
        let relative = validate_entry(&entry)?;
        if !seen.insert(relative.clone()) {
            return Err(format!(
                "bundle has a duplicate entry {}",
                relative.display()
            ));
        }
        if !entry.is_dir() {
            total = total
                .checked_add(entry.size())
                .ok_or_else(|| "bundle size overflow".to_owned())?;
            if total > MAX_TOTAL_BYTES {
                return Err(format!(
                    "bundle expands to more than {MAX_TOTAL_BYTES} bytes"
                ));
            }
        }
    }

    let parent = assets_dir
        .parent()
        .ok_or_else(|| "assets directory has no parent".to_owned())?;
    fs::create_dir_all(parent)
        .map_err(|error| format!("cannot create {}: {error}", parent.display()))?;
    let temporary = parent.join(format!(
        ".assets-install-{}-{}",
        std::process::id(),
        monotonic_id()
    ));
    if temporary.exists() {
        fs::remove_dir_all(&temporary)
            .map_err(|error| format!("cannot clear staging directory: {error}"))?;
    }
    fs::create_dir(&temporary)
        .map_err(|error| format!("cannot create {}: {error}", temporary.display()))?;

    let extraction = (|| -> Result<(), String> {
        let mut actual_total = 0u64;
        for index in 0..archive.len() {
            let mut entry = archive
                .by_index(index)
                .map_err(|error| format!("cannot read bundle entry {index}: {error}"))?;
            let relative = validate_entry(&entry)?;
            let target = temporary.join(&relative);
            if entry.is_dir() {
                fs::create_dir_all(&target)
                    .map_err(|error| format!("cannot create {}: {error}", target.display()))?;
                let mode = entry
                    .unix_mode()
                    .map(|mode| mode & 0o777)
                    .filter(|mode| *mode != 0)
                    .unwrap_or(0o755);
                fs::set_permissions(&target, fs::Permissions::from_mode(mode))
                    .map_err(|error| format!("cannot chmod {}: {error}", target.display()))?;
                continue;
            }
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent)
                    .map_err(|error| format!("cannot create {}: {error}", parent.display()))?;
            }
            let mode = entry
                .unix_mode()
                .map(|mode| mode & 0o777)
                .filter(|mode| *mode != 0)
                .unwrap_or(0o644);
            let mut output = OpenOptions::new()
                .create_new(true)
                .write(true)
                .mode(0o600)
                .open(&target)
                .map_err(|error| format!("cannot create {}: {error}", target.display()))?;
            let written = copy_capped(&mut entry, &mut output, MAX_ENTRY_BYTES)?;
            if written != entry.size() {
                return Err(format!(
                    "bundle entry {} is {} bytes but declared {}",
                    relative.display(),
                    written,
                    entry.size()
                ));
            }
            actual_total = actual_total
                .checked_add(written)
                .ok_or_else(|| "bundle size overflow".to_owned())?;
            if actual_total > MAX_TOTAL_BYTES {
                return Err(format!("bundle expands beyond {MAX_TOTAL_BYTES} bytes"));
            }
            output
                .sync_all()
                .map_err(|error| format!("cannot fsync {}: {error}", target.display()))?;
            fs::set_permissions(&target, fs::Permissions::from_mode(mode))
                .map_err(|error| format!("cannot chmod {}: {error}", target.display()))?;
        }
        Ok(())
    })();
    if let Err(error) = extraction {
        let _ = fs::remove_dir_all(&temporary);
        return Err(error);
    }

    let backup = parent.join(format!(
        ".assets-backup-{}-{}",
        std::process::id(),
        monotonic_id()
    ));
    let had_assets = assets_dir.exists();
    if had_assets {
        fs::rename(assets_dir, &backup)
            .map_err(|error| format!("cannot stage existing assets: {error}"))?;
    }
    match fs::rename(&temporary, assets_dir) {
        Ok(()) => {
            if had_assets {
                let _ = fs::remove_dir_all(&backup);
            }
            Ok(())
        }
        Err(error) => {
            if had_assets {
                let _ = fs::rename(&backup, assets_dir);
            }
            let _ = fs::remove_dir_all(&temporary);
            Err(format!("cannot activate installed assets: {error}"))
        }
    }
}

fn validate_entry<R: std::io::Read>(entry: &zip::read::ZipFile<'_, R>) -> Result<PathBuf, String> {
    let name = entry
        .name()
        .strip_suffix('/')
        .unwrap_or_else(|| entry.name());
    if name.is_empty() {
        return Err("bundle entry has an empty name".to_owned());
    }
    if name.starts_with('/') || name.contains('\\') || name.contains('\0') {
        return Err(format!(
            "bundle entry {name:?} is not a confined relative path"
        ));
    }
    let mut relative = PathBuf::new();
    for component in Path::new(name).components() {
        match component {
            Component::Normal(component) => {
                let text = component
                    .to_str()
                    .ok_or_else(|| format!("bundle entry {name:?} is not valid UTF-8"))?;
                if text.len() > 255 {
                    return Err(format!("bundle entry {name:?} has an overlong component"));
                }
                relative.push(text);
            }
            _ => {
                return Err(format!(
                    "bundle entry {name:?} has a traversal or root component"
                ));
            }
        }
    }
    if entry
        .unix_mode()
        .map(|mode| mode & 0o170000 == 0o120000)
        .unwrap_or(false)
    {
        return Err(format!("bundle entry {name:?} is a symlink"));
    }
    Ok(relative)
}

fn copy_capped(input: &mut impl Read, output: &mut impl Write, cap: u64) -> Result<u64, String> {
    let mut buffer = [0u8; 64 * 1024];
    let mut total = 0u64;
    loop {
        let read = input
            .read(&mut buffer)
            .map_err(|error| format!("cannot read bundle entry: {error}"))?;
        if read == 0 {
            return Ok(total);
        }
        total = total
            .checked_add(read as u64)
            .ok_or_else(|| "bundle size overflow".to_owned())?;
        if total > cap {
            return Err(format!("bundle entry exceeds the {cap} byte cap"));
        }
        output
            .write_all(&buffer[..read])
            .map_err(|error| format!("cannot write bundle entry: {error}"))?;
    }
}

fn monotonic_id() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};
    static ID: AtomicU64 = AtomicU64::new(0);
    ID.fetch_add(1, Ordering::Relaxed)
}
