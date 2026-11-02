#!/usr/bin/env python3
"""Focused tests for isolated Rust bootstrap resolution and checksum failure."""
from __future__ import annotations

import os
import platform
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WRAPPER = ROOT / "android/tools/with-rust-toolchain.sh"


def executable(path: Path, body: str) -> None:
    path.write_text("#!/bin/sh\n" + body, encoding="utf-8")
    path.chmod(0o755)


def pinned_digests() -> tuple[str, str]:
    """Read the pinned manifest/installer digests the wrapper will compare."""
    text = WRAPPER.read_text(encoding="utf-8")
    manifest = re.search(
        r'RUST_CHANNEL_MANIFEST_SHA256="([0-9a-f]{64})"', text
    )
    assert manifest is not None, "manifest digest constant is missing"
    system = platform.system()
    machine = platform.machine()
    if machine in ("x86_64", "amd64"):
        arch = "x86_64"
    elif machine in ("aarch64", "arm64"):
        arch = "aarch64"
    else:
        raise unittest.SkipTest(f"unsupported fixture host architecture: {machine}")
    triple = {
        ("Linux", "x86_64"): "x86_64-unknown-linux-gnu",
        ("Linux", "aarch64"): "aarch64-unknown-linux-gnu",
        ("Darwin", "x86_64"): "x86_64-apple-darwin",
        ("Darwin", "aarch64"): "aarch64-apple-darwin",
    }.get((system, arch))
    if triple is None:
        raise unittest.SkipTest(f"unsupported fixture host: {system}/{machine}")
    installer = re.search(
        re.escape(triple) + r"\) printf '%s\\n' ([0-9a-f]{64})", text
    )
    assert installer is not None, f"installer digest for {triple} is missing"
    return manifest.group(1), installer.group(1)


class RustToolchainWrapperTest(unittest.TestCase):
    def test_check_reuses_adequate_caller_toolchain_without_install(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            executable(bin_dir / "rustc", "echo 'rustc 1.99.1 (fixture)'\n")
            executable(bin_dir / "cargo", "echo 'cargo 1.99.1 (fixture)'\n")
            env = {
                **os.environ,
                "PATH": f"{bin_dir}:{os.defpath}",
                "HOME": str(root / "home"),
                "XDG_CACHE_HOME": str(root / "cache"),
            }
            result = subprocess.run(
                ["bash", str(WRAPPER), "--check"], env=env, text=True,
                capture_output=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Rust toolchain OK", result.stdout)
            self.assertFalse((root / "cache/pdsh/rustup").exists())

    def test_check_fails_closed_when_rust_is_missing(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            env = {
                **os.environ,
                "PATH": os.defpath,
                "HOME": str(root / "home"),
                "XDG_CACHE_HOME": str(root / "cache"),
            }
            result = subprocess.run(
                ["bash", str(WRAPPER), "--check"], env=env, text=True,
                capture_output=True, check=False,
            )
            self.assertEqual(result.returncode, 2)
            self.assertIn("run android/tools/with-rust-toolchain.sh --install", result.stderr)
            self.assertFalse((root / "cache/pdsh/rustup").exists())

    def test_install_rejects_unverified_manifest_fixture_before_execution(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            executable(bin_dir / "curl", 'while [ "$1" != "--output" ]; do shift; done; shift; printf fixture > "$1"\n')
            env = {
                **os.environ,
                "PATH": f"{bin_dir}:{os.defpath}",
                "HOME": str(root / "home"),
                "XDG_CACHE_HOME": str(root / "cache"),
                "TMPDIR": str(root),
            }
            result = subprocess.run(
                ["bash", str(WRAPPER), "--install"], env=env, text=True,
                capture_output=True, check=False,
            )
            self.assertEqual(result.returncode, 2)
            self.assertIn("channel manifest checksum mismatch", result.stderr)
            self.assertFalse((root / "cache/pdsh/rustup/toolchains").exists())

    def test_install_uses_rustup_init_128_flags_and_explicit_cache_homes(self):
        manifest_sha, installer_sha = pinned_digests()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            # Force the install branch without network access.
            executable(bin_dir / "rustc", "echo 'rustc 1.85.0 (old fixture)'\n")
            executable(bin_dir / "cargo", "echo 'cargo 1.85.0 (old fixture)'\n")
            installer = root / "rustup-init.fixture"
            installer.write_text(
                """#!/bin/sh
printf '%s\\n' "$*" > "$FAKE_RUSTUP_INIT_LOG"
case " $* " in
  *" --no-self-update "*)
    printf "error: unexpected argument '--no-self-update' found\\n" >&2
    exit 2
    ;;
esac
mkdir -p "$CARGO_HOME/bin"
cat > "$CARGO_HOME/bin/rustup" <<'RUSTUP'
#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE_RUSTUP_LOG"
exit 0
RUSTUP
for tool in rustc cargo; do
  cat > "$CARGO_HOME/bin/$tool" <<RUSTC
#!/bin/sh
echo '$tool 1.99.1 (fixture)'
RUSTC
  chmod 755 "$CARGO_HOME/bin/$tool"
done
chmod 755 "$CARGO_HOME/bin/rustup"
""",
                encoding="utf-8",
            )
            executable(
                bin_dir / "curl",
                """
out=""
url=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output) out="$2"; shift 2 ;;
    http*) url="$1"; shift ;;
    *) shift ;;
  esac
done
[ -n "$out" ] || exit 90
case "$url" in
  *channel-rust-*) printf 'manifest fixture\\n' > "$out" ;;
  *rustup-init*) cp "$FAKE_INSTALLER" "$out" ;;
  *) exit 91 ;;
esac
""",
            )
            executable(
                bin_dir / "shasum",
                """
last=""
for arg in "$@"; do last="$arg"; done
case "$last" in
  *channel-rust-*) printf '%s  %s\\n' "$FAKE_MANIFEST_SHA" "$last" ;;
  *rustup-init*) printf '%s  %s\\n' "$FAKE_INSTALLER_SHA" "$last" ;;
  *) exit 92 ;;
esac
""",
            )
            env = {
                **os.environ,
                "PATH": f"{bin_dir}:{os.defpath}",
                "HOME": str(root / "home"),
                "XDG_CACHE_HOME": str(root / "xdg"),
                "CARGO_HOME": str(root / "cargo"),
                "RUSTUP_HOME": str(root / "rustup"),
                "TMPDIR": str(root),
                "FAKE_INSTALLER": str(installer),
                "FAKE_MANIFEST_SHA": manifest_sha,
                "FAKE_INSTALLER_SHA": installer_sha,
                "FAKE_RUSTUP_INIT_LOG": str(root / "rustup-init.args"),
                "FAKE_RUSTUP_LOG": str(root / "rustup.args"),
            }
            result = subprocess.run(
                ["bash", str(WRAPPER), "--install"], env=env, text=True,
                capture_output=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("rustc 1.99.1 (fixture)", result.stdout)

            init_args = (root / "rustup-init.args").read_text(encoding="utf-8")
            self.assertNotIn("--no-self-update", init_args)
            self.assertIn("--default-toolchain 1.99.1", init_args)
            self.assertIn("--no-modify-path", init_args)
            self.assertIn("--profile minimal", init_args)

            rustup_args = (root / "rustup.args").read_text(encoding="utf-8")
            self.assertIn("toolchain install 1.99.1 --profile minimal --no-self-update", rustup_args)
            self.assertIn("default 1.99.1", rustup_args)
            self.assertTrue((root / "cargo/bin/rustup").is_file())
            # Explicit homes win over XDG, so no cache was created there.
            self.assertFalse((root / "xdg").exists())


if __name__ == "__main__":
    unittest.main()
