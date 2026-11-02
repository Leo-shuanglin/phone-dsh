#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# Reproducible, task-local JDK 17 + Android SDK bootstrap for the PDSH Android
# tree. Nothing is installed into a shared machine location: the default target
# is a `task-writable/` sibling of this checkout, and every path can be
# overridden from the environment.
#
# Usage:
#   bash android/tools/bootstrap-android-toolchain.sh          # install/repair
#   bash android/tools/bootstrap-android-toolchain.sh --check  # verify only
#
# Environment:
#   PDSH_TOOLCHAIN_DIR  root for the JDK/download cache (default:
#                       <checkout-parent>/task-writable)
#   ANDROID_HOME        existing SDK root; when set, packages are installed/read
#                       there instead of <PDSH_TOOLCHAIN_DIR>/android-sdk
#   PDSH_JAVA_HOME      explicit JDK 17 home (must already be JDK 17)
#   JAVA_HOME           reused only when it already is JDK 17; otherwise a JDK 17
#                       is installed under PDSH_TOOLCHAIN_DIR
#   PDSH_LOCAL_PROPERTIES
#                       path written by install mode (default:
#                       <checkout>/android/local.properties)
#   PDSH_CURL           downloader executable (default: curl)
#   PDSH_JDK_URL        JDK 17 archive URL (default: Adoptium Temurin 17, host arch)
#   PDSH_CMDLINE_TOOLS_URL
#                       Android command-line tools ZIP URL
#   PDSH_PLATFORM       SDK platform package (default: platforms;android-35)
#   PDSH_BUILD_TOOLS    SDK build-tools package (default: build-tools;35.0.0)
#
# The script is intentionally non-interactive: SDK licences are accepted with a
# stream of `y` on stdin, never by an operator prompt. Installer stdout/stderr is
# always printed; a download, extraction, licence or package failure prints the
# exact command and exit code and exits with that code.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

DEFAULT_WORKSPACE_PARENT="$(cd -- "$REPO_ROOT/.." && pwd)"
TOOLCHAIN_DIR="${PDSH_TOOLCHAIN_DIR:-$DEFAULT_WORKSPACE_PARENT/task-writable}"
LOCAL_PROPERTIES="${PDSH_LOCAL_PROPERTIES:-$REPO_ROOT/android/local.properties}"
CURL="${PDSH_CURL:-curl}"
PLATFORM_PACKAGE="${PDSH_PLATFORM:-platforms;android-35}"
BUILD_TOOLS_PACKAGE="${PDSH_BUILD_TOOLS:-build-tools;35.0.0}"
PLATFORM_DIR_NAME="${PLATFORM_PACKAGE#platforms;}"
BUILD_TOOLS_DIR_NAME="${BUILD_TOOLS_PACKAGE#build-tools;}"
SDK_PACKAGES=("platform-tools" "$PLATFORM_PACKAGE" "$BUILD_TOOLS_PACKAGE")

case "$(uname -m)" in
  x86_64 | amd64) JDK_ARCH=x64 ;;
  aarch64 | arm64) JDK_ARCH=aarch64 ;;
  *) echo "ERROR: unsupported host architecture: $(uname -m)" >&2; exit 2 ;;
esac

JDK_URL="${PDSH_JDK_URL:-https://api.adoptium.net/v3/binary/latest/17/ga/linux/${JDK_ARCH}/jdk/hotspot/normal/eclipse}"
# Pinned command-line tools build: Google does not publish a "latest" URL that
# is safe to depend on, and this build understands platform 35.
CMDLINE_TOOLS_URL="${PDSH_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"

MODE=install
for arg in "$@"; do
  case "$arg" in
    --check) MODE=check ;;
    -h | --help)
      sed -n '3,42p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

abs_path() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *) printf '%s/%s\n' "$(pwd)" "$1" ;;
  esac
}

TOOLCHAIN_DIR="$(abs_path "$TOOLCHAIN_DIR")"
while [ "$TOOLCHAIN_DIR" != "/" ] && [ "${TOOLCHAIN_DIR%/}" != "$TOOLCHAIN_DIR" ]; do
  TOOLCHAIN_DIR="${TOOLCHAIN_DIR%/}"
done
if [ -n "${ANDROID_HOME:-}" ]; then
  SDK_DIR="$(abs_path "$ANDROID_HOME")"
else
  SDK_DIR="$TOOLCHAIN_DIR/android-sdk"
fi
while [ "$SDK_DIR" != "/" ] && [ "${SDK_DIR%/}" != "$SDK_DIR" ]; do
  SDK_DIR="${SDK_DIR%/}"
done
LOCAL_PROPERTIES="$(abs_path "$LOCAL_PROPERTIES")"
SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
DOWNLOADS="$TOOLCHAIN_DIR/downloads"
JDK_HOME=""

say() { printf '%s\n' "$*"; }
ok() { printf 'OK: %s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die() {
  local message="$1"
  local code="${2:-1}"
  printf 'ERROR: %s\n' "$message" >&2
  exit "$code"
}

# Run an installer command verbatim: the command is printed, its own stdout and
# stderr stay visible, and a nonzero status is reported with its exact code.
run_installer() {
  local rc=0
  say "+ $*"
  set +e
  "$@"
  rc=$?
  set -e
  if [ "$rc" -ne 0 ]; then
    die "command failed (exit $rc): $*" "$rc"
  fi
}

is_jdk17() {
  local home="$1"
  [ -x "$home/bin/java" ] && [ -x "$home/bin/javac" ] || return 1
  "$home/bin/javac" -version 2>&1 | grep -Eq '^javac 17(\.|$)'
}

java_version() {
  "$1/bin/java" -version 2>&1 | sed -n '1p'
}

javac_version() {
  "$1/bin/javac" -version 2>&1 | sed -n '1p'
}

resolve_jdk() {
  if [ -n "${PDSH_JAVA_HOME:-}" ]; then
    if ! is_jdk17 "$PDSH_JAVA_HOME"; then
      die "PDSH_JAVA_HOME is not JDK 17: $PDSH_JAVA_HOME"
    fi
    printf '%s\n' "$(abs_path "$PDSH_JAVA_HOME")"
    return
  fi
  if [ -n "${JAVA_HOME:-}" ] && is_jdk17 "$JAVA_HOME"; then
    printf '%s\n' "$(abs_path "$JAVA_HOME")"
    return
  fi
  if [ -n "${JAVA_HOME:-}" ]; then
    warn "JAVA_HOME is not JDK 17 ($JAVA_HOME); installing JDK 17 under $TOOLCHAIN_DIR"
  fi
  printf '%s\n' "$TOOLCHAIN_DIR/jdk-17"
}

download() {
  local url="$1"
  local destination="$2"
  local part="$destination.part"
  mkdir -p "$(dirname -- "$destination")"
  if [ -s "$destination" ]; then
    say "reusing cached download: $destination"
    return
  fi
  rm -f -- "$part"
  run_installer "$CURL" --fail --location --retry 3 --output "$part" "$url"
  mv -- "$part" "$destination"
  ok "downloaded $destination"
}

install_jdk() {
  local destination="$1"
  local archive="$DOWNLOADS/jdk-17-${JDK_ARCH}.tar.gz"
  local extract_dir
  local source_root

  if is_jdk17 "$destination"; then
    ok "JDK 17 already present: $destination"
    return
  fi

  if [ -e "$destination" ]; then
    case "$destination" in
      "$TOOLCHAIN_DIR"/*) ;;
      *) die "refusing to replace a JDK outside PDSH_TOOLCHAIN_DIR: $destination" ;;
    esac
    warn "removing incomplete JDK at $destination"
    rm -rf -- "$destination"
  fi

  mkdir -p "$TOOLCHAIN_DIR"
  download "$JDK_URL" "$archive"

  extract_dir="$(mktemp -d "$TOOLCHAIN_DIR/.jdk-extract.XXXXXX")"
  # shellcheck disable=SC2064
  trap "rm -rf -- '$extract_dir'" EXIT
  run_installer tar -xzf "$archive" -C "$extract_dir"
  source_root="$(find "$extract_dir" -maxdepth 3 -type f -path '*/bin/javac' -print -quit)"
  [ -n "$source_root" ] || die "JDK archive contains no bin/javac: $archive"
  source_root="${source_root%/bin/javac}"
  mv -- "$source_root" "$destination"
  rm -rf -- "$extract_dir"
  trap - EXIT

  is_jdk17 "$destination" || die "installed JDK is not JDK 17: $destination ($(javac_version "$destination" || true))"
  ok "installed JDK 17 at $destination"
}

install_cmdline_tools() {
  local archive="$DOWNLOADS/commandlinetools-linux.zip"
  local extract_dir
  local source_root

  if [ -x "$SDKMANAGER" ]; then
    ok "Android command-line tools already present: $SDKMANAGER"
    return
  fi

  mkdir -p "$SDK_DIR"
  download "$CMDLINE_TOOLS_URL" "$archive"

  extract_dir="$(mktemp -d "$TOOLCHAIN_DIR/.sdk-extract.XXXXXX")"
  # shellcheck disable=SC2064
  trap "rm -rf -- '$extract_dir'" EXIT
  run_installer unzip "$archive" -d "$extract_dir"
  source_root="$extract_dir/cmdline-tools"
  [ -f "$source_root/bin/sdkmanager" ] || die "command-line tools archive has no cmdline-tools/bin/sdkmanager: $archive"

  if [ -e "$SDK_DIR/cmdline-tools/latest" ]; then
    warn "removing incomplete command-line tools at $SDK_DIR/cmdline-tools/latest"
    rm -rf -- "$SDK_DIR/cmdline-tools/latest"
  fi
  mkdir -p "$SDK_DIR/cmdline-tools"
  mv -- "$source_root" "$SDK_DIR/cmdline-tools/latest"
  rm -rf -- "$extract_dir"
  trap - EXIT
  run_installer chmod +x "$SDKMANAGER"
  [ -x "$SDKMANAGER" ] || die "sdkmanager is not executable after extraction: $SDKMANAGER"
  ok "installed Android command-line tools at $SDKMANAGER"
}

accept_licenses() {
  local rc=0
  say "+ yes | $SDKMANAGER --sdk_root=$SDK_DIR --licenses"
  # `yes` gets SIGPIPE once sdkmanager has read enough; that is expected and is
  # not the installer's status. PIPESTATUS[1] is sdkmanager's exit code.
  set +e
  set +o pipefail
  yes | "$SDKMANAGER" "--sdk_root=$SDK_DIR" --licenses
  rc=${PIPESTATUS[1]}
  set -o pipefail
  set -e
  if [ "$rc" -ne 0 ]; then
    die "licence acceptance failed (exit $rc): yes | $SDKMANAGER --sdk_root=$SDK_DIR --licenses" "$rc"
  fi
  ok "Android SDK licences accepted"
}

install_packages() {
  run_installer "$SDKMANAGER" "--sdk_root=$SDK_DIR" "${SDK_PACKAGES[@]}"
}

write_local_properties() {
  local directory temporary
  directory="$(dirname -- "$LOCAL_PROPERTIES")"
  mkdir -p "$directory"
  temporary="$(mktemp "$LOCAL_PROPERTIES.XXXXXX")"
  # Keep any non-sdk properties a developer already has; replace only sdk.dir.
  if [ -f "$LOCAL_PROPERTIES" ]; then
    grep -v -E '^[[:space:]]*sdk\.dir[[:space:]]*=' "$LOCAL_PROPERTIES" >"$temporary" || true
  fi
  printf 'sdk.dir=%s\n' "$SDK_DIR" >>"$temporary"
  if [ -f "$LOCAL_PROPERTIES" ] && cmp -s "$temporary" "$LOCAL_PROPERTIES"; then
    rm -f -- "$temporary"
    ok "local.properties already points at $SDK_DIR"
    return
  fi
  mv -- "$temporary" "$LOCAL_PROPERTIES"
  ok "wrote $LOCAL_PROPERTIES (sdk.dir=$SDK_DIR)"
}

list_installed() {
  local listing="$1"
  local rc=0
  say "+ $SDKMANAGER --sdk_root=$SDK_DIR --list_installed"
  set +e
  "$SDKMANAGER" "--sdk_root=$SDK_DIR" --list_installed 2>&1 | tee "$listing"
  rc=${PIPESTATUS[0]}
  set -e
  if [ "$rc" -ne 0 ]; then
    die "sdkmanager --list_installed failed (exit $rc): $SDKMANAGER --sdk_root=$SDK_DIR --list_installed" "$rc"
  fi
}

verify_packages() {
  local listing="$1"
  local package
  local failures=0
  for package in "${SDK_PACKAGES[@]}"; do
    if grep -Fq -- "$package" "$listing"; then
      printf 'PACKAGE %s=installed\n' "$package"
    else
      printf 'PACKAGE %s=MISSING\n' "$package"
      failures=$((failures + 1))
    fi
  done
  [ -d "$SDK_DIR/platforms/$PLATFORM_DIR_NAME" ] || {
    printf 'PACKAGE %s=directory-missing (%s)\n' "$PLATFORM_PACKAGE" "$SDK_DIR/platforms/$PLATFORM_DIR_NAME"
    failures=$((failures + 1))
  }
  [ -d "$SDK_DIR/build-tools/$BUILD_TOOLS_DIR_NAME" ] || {
    printf 'PACKAGE %s=directory-missing (%s)\n' "$BUILD_TOOLS_PACKAGE" "$SDK_DIR/build-tools/$BUILD_TOOLS_DIR_NAME"
    failures=$((failures + 1))
  }
  [ -x "$SDK_DIR/platform-tools/adb" ] || {
    printf 'PACKAGE platform-tools=adb-missing (%s)\n' "$SDK_DIR/platform-tools/adb"
    failures=$((failures + 1))
  }
  return "$failures"
}

print_paths() {
  say "PDSH_TOOLCHAIN_DIR=$TOOLCHAIN_DIR"
  say "JAVA_HOME=$JDK_HOME"
  say "ANDROID_HOME=$SDK_DIR"
  say "SDKMANAGER=$SDKMANAGER"
  say "LOCAL_PROPERTIES=$LOCAL_PROPERTIES"
}

check_toolchain() {
  local failures=0
  local listing
  JDK_HOME="$(resolve_jdk)"
  print_paths
  say "MODE=check (no install; local.properties is not written)"

  if is_jdk17 "$JDK_HOME"; then
    say "JAVA=$(java_version "$JDK_HOME")"
    say "JAVAC=$(javac_version "$JDK_HOME")"
    ok "java and javac are JDK 17"
  else
    say "JAVA=MISSING"
    say "JAVAC=MISSING"
    warn "no JDK 17 at $JDK_HOME"
    failures=$((failures + 1))
  fi

  if [ ! -x "$SDKMANAGER" ]; then
    say "SDKMANAGER=MISSING"
    failures=$((failures + 1))
  elif [ "$failures" -eq 0 ]; then
    export JAVA_HOME="$JDK_HOME"
    export PATH="$JDK_HOME/bin:$PATH"
    listing="$(mktemp "${TMPDIR:-/tmp}/pdsh-sdkmanager-list.XXXXXX")"
    list_installed "$listing"
    verify_packages "$listing" || failures=$((failures + 1))
    rm -f -- "$listing"
  else
    say "PACKAGE check=SKIPPED (JDK 17 missing)"
    failures=$((failures + 1))
  fi

  if [ "$failures" -ne 0 ]; then
    die "toolchain check failed: $failures problem(s); run bash android/tools/bootstrap-android-toolchain.sh"
  fi
  ok "toolchain check passed"
}

install_toolchain() {
  mkdir -p "$TOOLCHAIN_DIR"
  JDK_HOME="$(resolve_jdk)"
  print_paths

  install_jdk "$JDK_HOME"
  install_cmdline_tools
  export JAVA_HOME="$JDK_HOME"
  export PATH="$JDK_HOME/bin:$SDK_DIR/platform-tools:$PATH"

  accept_licenses
  install_packages

  local listing
  local verify_rc=0
  listing="$(mktemp "${TMPDIR:-/tmp}/pdsh-sdkmanager-list.XXXXXX")"
  list_installed "$listing"
  set +e
  verify_packages "$listing"
  verify_rc=$?
  set -e
  if [ "$verify_rc" -ne 0 ]; then
    rm -f -- "$listing"
    die "Android SDK package verification failed for $verify_rc package(s)"
  fi
  rm -f -- "$listing"

  is_jdk17 "$JDK_HOME" || die "java/javac are not JDK 17: $JDK_HOME"
  write_local_properties
  ok "Android toolchain ready (JDK: $JDK_HOME; SDK: $SDK_DIR)"
}

if [ "$MODE" = check ]; then
  check_toolchain
else
  install_toolchain
fi
