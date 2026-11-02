#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Tests for android/tools/bootstrap-android-toolchain.sh.

The tests execute the real shell script. External network access is replaced by
a fake `curl` that serves local fixture archives, and the extracted command-line
tools fixture carries a fake `sdkmanager`; every path lives in a temporary
directory, so no JDK, Android SDK or network resource is installed outside it.
"""

from __future__ import annotations

import io
import os
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().with_name("bootstrap-android-toolchain.sh")

FAKE_CURL = r"""#!/usr/bin/env bash
set -u
printf '%s\n' "$*" >> "${FAKE_CURL_LOG:-/dev/null}"
out=""
url=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output) out="$2"; shift 2 ;;
    --retry) shift 2 ;;
    --*) shift ;;
    *) url="$1"; shift ;;
  esac
done
if [ -n "${FAKE_CURL_EXIT:-}" ]; then
  echo "fake curl: forced download failure" >&2
  exit "$FAKE_CURL_EXIT"
fi
[ -n "$out" ] || { echo "fake curl: no --output" >&2; exit 2; }
case "$url" in
  *jdk*|*eclipse*) cp "${FAKE_JDK_ARCHIVE:?}" "$out" ;;
  *cmdline*|*commandline*) cp "${FAKE_SDK_ARCHIVE:?}" "$out" ;;
  *) echo "fake curl: unexpected URL $url" >&2; exit 3 ;;
esac
"""

FAKE_SDKMANAGER = r"""#!/usr/bin/env bash
set -u
printf '%s\n' "$*" >> "${FAKE_SDKMANAGER_LOG:-/dev/null}"
sdk_root=""
for arg in "$@"; do
  case "$arg" in
    --sdk_root=*) sdk_root="${arg#*=}" ;;
  esac
done
[ -n "$sdk_root" ] || { echo "fake sdkmanager: no --sdk_root" >&2; exit 2; }
args=" $* "
case "$args" in
  *" --licenses "*)
    IFS= read -r _ || true
    if [ -n "${FAKE_SDKMANAGER_LICENSE_EXIT:-}" ]; then
      echo "fake sdkmanager: forced licence failure" >&2
      exit "$FAKE_SDKMANAGER_LICENSE_EXIT"
    fi
    echo "All SDK package licenses accepted."
    exit 0
    ;;
  *" --list_installed "*)
    echo "Installed packages:"
    [ -d "$sdk_root/platform-tools" ] && echo "  platform-tools | 35.0.1 | Android SDK Platform-Tools"
    [ -d "$sdk_root/platforms/android-35" ] && echo "  platforms;android-35 | 2 | Android SDK Platform 35"
    [ -d "$sdk_root/build-tools/35.0.0" ] && echo "  build-tools;35.0.0 | 35.0.0 | Android SDK Build-Tools 35"
    exit 0
    ;;
esac
if [ -n "${FAKE_SDKMANAGER_INSTALL_EXIT:-}" ]; then
  echo "fake sdkmanager: forced package failure" >&2
  exit "$FAKE_SDKMANAGER_INSTALL_EXIT"
fi
for arg in "$@"; do
  case "$arg" in
    platform-tools)
      mkdir -p "$sdk_root/platform-tools"
      : > "$sdk_root/platform-tools/adb"
      chmod +x "$sdk_root/platform-tools/adb"
      ;;
    'platforms;android-35')
      mkdir -p "$sdk_root/platforms/android-35"
      : > "$sdk_root/platforms/android-35/android.jar"
      ;;
    'build-tools;35.0.0')
      mkdir -p "$sdk_root/build-tools/35.0.0"
      : > "$sdk_root/build-tools/35.0.0/aapt2"
      ;;
  esac
done
exit 0
"""

FAKE_JAVA = """#!/usr/bin/env bash
echo 'openjdk version "17.0.99" 2026-01-01' >&2
echo 'OpenJDK Runtime Environment Temurin-17.0.99 (build 17.0.99+9)' >&2
"""

FAKE_JAVAC = """#!/usr/bin/env bash
echo 'javac 17.0.99'
"""


def _tar_member(archive: tarfile.TarFile, name: str, data: bytes, executable: bool = False):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mtime = 0
    info.mode = 0o755 if executable else 0o644
    archive.addfile(info, io.BytesIO(data))


def write_jdk_archive(path: Path):
    with tarfile.open(path, "w:gz") as archive:
        _tar_member(archive, "jdk-17.0.99/bin/java", FAKE_JAVA.encode(), executable=True)
        _tar_member(archive, "jdk-17.0.99/bin/javac", FAKE_JAVAC.encode(), executable=True)
        _tar_member(archive, "jdk-17.0.99/release", b"JAVA_VERSION=\"17.0.99\"\n")


def write_sdk_archive(path: Path):
    info = zipfile.ZipInfo("cmdline-tools/bin/sdkmanager")
    info.external_attr = 0o755 << 16
    info.create_system = 3
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(info, FAKE_SDKMANAGER.encode())


def write_jdk_home(home: Path):
    (home / "bin").mkdir(parents=True)
    for name, text in (("java", FAKE_JAVA), ("javac", FAKE_JAVAC)):
        target = home / "bin" / name
        target.write_text(text, encoding="utf-8")
        target.chmod(0o755)


class BootstrapToolchainTests(unittest.TestCase):
    def setUp(self):
        self._temp = tempfile.TemporaryDirectory()
        self.root = Path(self._temp.name)
        self.toolchain = self.root / "toolchain"
        self.repo = self.root / "repo"
        self.local_properties = self.repo / "android" / "local.properties"
        self.fake_bin = self.root / "fake-bin"
        self.fake_bin.mkdir()
        self.curl_log = self.root / "curl.log"
        self.sdkmanager_log = self.root / "sdkmanager.log"
        self.jdk_archive = self.root / "jdk17.tar.gz"
        self.sdk_archive = self.root / "cmdline-tools.zip"
        write_jdk_archive(self.jdk_archive)
        write_sdk_archive(self.sdk_archive)
        self.curl = self.fake_bin / "fake-curl"
        self.curl.write_text(FAKE_CURL, encoding="utf-8")
        self.curl.chmod(0o755)

    def tearDown(self):
        self._temp.cleanup()

    def environment(self, **overrides):
        env = os.environ.copy()
        for key in (
            "PDSH_TOOLCHAIN_DIR",
            "PDSH_JAVA_HOME",
            "JAVA_HOME",
            "ANDROID_HOME",
            "PDSH_LOCAL_PROPERTIES",
            "PDSH_CURL",
            "PDSH_JDK_URL",
            "PDSH_CMDLINE_TOOLS_URL",
            "FAKE_CURL_EXIT",
            "FAKE_CURL_LOG",
            "FAKE_JDK_ARCHIVE",
            "FAKE_SDK_ARCHIVE",
            "FAKE_SDKMANAGER_LOG",
            "FAKE_SDKMANAGER_LICENSE_EXIT",
            "FAKE_SDKMANAGER_INSTALL_EXIT",
        ):
            env.pop(key, None)
        env.update(
            {
                "PDSH_TOOLCHAIN_DIR": str(self.toolchain),
                "PDSH_LOCAL_PROPERTIES": str(self.local_properties),
                "PDSH_CURL": str(self.curl),
                "PDSH_JDK_URL": "https://fake.invalid/jdk17.tar.gz",
                "PDSH_CMDLINE_TOOLS_URL": "https://fake.invalid/cmdline-tools.zip",
                "FAKE_CURL_LOG": str(self.curl_log),
                "FAKE_JDK_ARCHIVE": str(self.jdk_archive),
                "FAKE_SDK_ARCHIVE": str(self.sdk_archive),
                "FAKE_SDKMANAGER_LOG": str(self.sdkmanager_log),
            }
        )
        env.update(overrides)
        return env

    def run_script(self, *args, script=SCRIPT, **overrides):
        return subprocess.run(
            ["bash", str(script), *args],
            cwd=str(self.root),
            env=self.environment(**overrides),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
            check=False,
        )

    def combined(self, proc):
        return proc.stdout + proc.stderr

    def test_default_paths_are_checkout_sibling_and_android_sdk(self):
        checkout = self.root / "checkout"
        tools = checkout / "android" / "tools"
        tools.mkdir(parents=True)
        copied = tools / SCRIPT.name
        shutil.copy2(SCRIPT, copied)
        env = self.environment()
        env.pop("PDSH_TOOLCHAIN_DIR")
        env.pop("PDSH_LOCAL_PROPERTIES")
        proc = subprocess.run(
            ["bash", str(copied), "--check"],
            cwd=str(self.root),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
            check=False,
        )
        output = self.combined(proc)
        self.assertNotEqual(proc.returncode, 0, output)
        self.assertIn("PDSH_TOOLCHAIN_DIR={}".format(self.root / "task-writable"), output)
        self.assertIn("ANDROID_HOME={}".format(self.root / "task-writable" / "android-sdk"), output)
        self.assertIn("LOCAL_PROPERTIES={}".format(checkout / "android" / "local.properties"), output)

    def test_check_reports_missing_toolchain_without_installing(self):
        proc = self.run_script("--check")
        output = self.combined(proc)
        self.assertEqual(proc.returncode, 1, output)
        self.assertIn("JAVA_HOME={}".format(self.toolchain / "jdk-17"), output)
        self.assertIn("JAVA=MISSING", output)
        self.assertIn("SDKMANAGER=MISSING", output)
        self.assertIn("MODE=check", output)
        self.assertFalse(self.toolchain.exists(), "check mode must not create the toolchain")
        self.assertFalse(self.local_properties.exists(), "check mode must not write local.properties")
        self.assertFalse(self.curl_log.exists(), "check mode must not download")

    def test_install_is_idempotent_and_writes_local_properties(self):
        first = self.run_script()
        output = self.combined(first)
        self.assertEqual(first.returncode, 0, output)
        self.assertTrue((self.toolchain / "jdk-17" / "bin" / "javac").is_file())
        self.assertTrue((self.toolchain / "android-sdk" / "platforms" / "android-35").is_dir())
        self.assertTrue((self.toolchain / "android-sdk" / "build-tools" / "35.0.0").is_dir())
        self.assertTrue((self.toolchain / "android-sdk" / "platform-tools" / "adb").is_file())
        self.assertIn(
            "sdk.dir={}".format(self.toolchain / "android-sdk"),
            self.local_properties.read_text(encoding="utf-8"),
        )
        downloads = self.curl_log.read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(downloads), 2, downloads)
        self.assertTrue(any("jdk17.tar.gz" in line for line in downloads), downloads)
        self.assertTrue(any("cmdline-tools.zip" in line for line in downloads), downloads)

        second = self.run_script()
        output = self.combined(second)
        self.assertEqual(second.returncode, 0, output)
        self.assertIn("JDK 17 already present", output)
        self.assertIn("command-line tools already present", output)
        self.assertEqual(
            len(self.curl_log.read_text(encoding="utf-8").splitlines()),
            2,
            "an idempotent second run must not download again",
        )

    def test_check_after_install_prints_versions_and_packages(self):
        install = self.run_script()
        self.assertEqual(install.returncode, 0, self.combined(install))
        proc = self.run_script("--check")
        output = self.combined(proc)
        self.assertEqual(proc.returncode, 0, output)
        self.assertIn("JAVA=openjdk version \"17.0.99\"", output)
        self.assertIn("JAVAC=javac 17.0.99", output)
        self.assertIn("PACKAGE platforms;android-35=installed", output)
        self.assertIn("PACKAGE build-tools;35.0.0=installed", output)
        self.assertIn("PACKAGE platform-tools=installed", output)
        self.assertIn("toolchain check passed", output)

    def test_android_home_override_is_honoured(self):
        sdk = self.root / "existing-sdk"
        proc = self.run_script(ANDROID_HOME=str(sdk))
        output = self.combined(proc)
        self.assertEqual(proc.returncode, 0, output)
        self.assertTrue((sdk / "platforms" / "android-35").is_dir())
        self.assertIn("ANDROID_HOME={}".format(sdk), output)
        self.assertIn("sdk.dir={}".format(sdk), self.local_properties.read_text(encoding="utf-8"))

    def test_local_properties_preserves_unrelated_keys_and_replaces_sdk_dir(self):
        self.local_properties.parent.mkdir(parents=True)
        self.local_properties.write_text("sdk.dir=/old/sdk\ncustom.key=keep\n", encoding="utf-8")
        proc = self.run_script()
        self.assertEqual(proc.returncode, 0, self.combined(proc))
        text = self.local_properties.read_text(encoding="utf-8")
        self.assertNotIn("/old/sdk", text)
        self.assertIn("custom.key=keep", text)
        self.assertEqual(text.count("sdk.dir="), 1, text)

    def test_download_failure_propagates_exit_code_and_command(self):
        proc = self.run_script(FAKE_CURL_EXIT="23")
        output = self.combined(proc)
        self.assertEqual(proc.returncode, 23, output)
        self.assertIn("command failed (exit 23)", output)
        self.assertIn("fake-curl", output)
        self.assertIn("https://fake.invalid/jdk17.tar.gz", output)
        self.assertFalse(self.local_properties.exists())

    def test_licence_failure_propagates_exit_code_and_command(self):
        write_jdk_home(self.toolchain / "jdk-17")
        proc = self.run_script(FAKE_SDKMANAGER_LICENSE_EXIT="9")
        output = self.combined(proc)
        self.assertEqual(proc.returncode, 9, output)
        self.assertIn("licence acceptance failed (exit 9)", output)
        self.assertIn("--licenses", output)
        self.assertFalse(self.local_properties.exists())

    def test_package_install_failure_propagates_exit_code_and_command(self):
        write_jdk_home(self.toolchain / "jdk-17")
        proc = self.run_script(FAKE_SDKMANAGER_INSTALL_EXIT="7")
        output = self.combined(proc)
        self.assertEqual(proc.returncode, 7, output)
        self.assertIn("command failed (exit 7)", output)
        self.assertIn("platform-tools", output)
        self.assertFalse(self.local_properties.exists())

    def test_check_detects_missing_platform_package(self):
        install = self.run_script()
        self.assertEqual(install.returncode, 0, self.combined(install))
        platform = self.toolchain / "android-sdk" / "platforms" / "android-35"
        shutil.rmtree(platform)
        proc = self.run_script("--check")
        output = self.combined(proc)
        self.assertEqual(proc.returncode, 1, output)
        self.assertIn("PACKAGE platforms;android-35=MISSING", output)
        self.assertIn("directory-missing", output)

    def test_check_never_downloads_even_with_a_failing_downloader(self):
        install = self.run_script()
        self.assertEqual(install.returncode, 0, self.combined(install))
        for archive in (self.toolchain / "downloads").iterdir():
            archive.unlink()
        proc = self.run_script("--check", FAKE_CURL_EXIT="99")
        output = self.combined(proc)
        self.assertEqual(proc.returncode, 0, output)
        self.assertNotIn("fake-curl", output)


if __name__ == "__main__":
    unittest.main(verbosity=2)
