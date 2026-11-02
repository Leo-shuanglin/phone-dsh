#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Run filesystem tests with ordinary owner permissions in root Fleet containers.

The phone and the Mac owner run unprivileged. Fleet's UID 0 otherwise bypasses
chmod and changes the meaning of the existing read-only-directory tests. Reduce
only this process tree's DAC bypass capabilities; never change test assertions,
file ownership, the container image, or a shared service.
"""
from __future__ import annotations

import ctypes
import os
from pathlib import Path
import sys
import tempfile


_VERIFY = "--verify-owner-permissions"


def verify_owner_permissions() -> None:
    with tempfile.TemporaryDirectory(prefix="pdsh-owner-permissions-") as temporary:
        directory = Path(temporary) / "read-only"
        directory.mkdir(mode=0o500)
        try:
            try:
                (directory / "must-not-write").write_bytes(b"fixture")
            except PermissionError:
                return
            raise RuntimeError("test process still bypasses read-only owner permissions")
        finally:
            directory.chmod(0o700)


def main(arguments: list[str]) -> int:
    if not arguments:
        print("usage: with-owner-fs-permissions.py <command> [args...]", file=sys.stderr)
        return 2
    verify = arguments[0] == _VERIFY
    if verify:
        arguments = arguments[1:]
        if not arguments:
            return 2
        verify_owner_permissions()
        print("test permissions: verified ordinary owner write denial", file=sys.stderr)
    elif sys.platform.startswith("linux") and os.geteuid() == 0:
        # PR_CAPBSET_DROP removes a capability only from this process and its
        # descendants. Exec once so UID 0 cannot regain DAC capabilities from
        # its bounding set; all existing task-owned 0700 caches remain readable.
        library = ctypes.CDLL(None, use_errno=True)
        library.prctl.argtypes = [ctypes.c_int] + [ctypes.c_ulong] * 4
        library.prctl.restype = ctypes.c_int
        status = dict(line.split(":", 1) for line in Path("/proc/self/status").read_text().splitlines() if ":" in line)
        bounding = int(status["CapBnd"].strip(), 16)
        for capability in (1, 2):  # CAP_DAC_OVERRIDE, CAP_DAC_READ_SEARCH
            # Some hardened workers already dropped all capabilities. They need
            # no privileged operation; the exec+write-denial proof is still run.
            if bounding & (1 << capability) == 0:
                continue
            if library.prctl(24, capability, 0, 0, 0) != 0:  # PR_CAPBSET_DROP
                number = ctypes.get_errno()
                raise OSError(number, "cannot reduce this test process's DAC capabilities")
        os.execv(sys.executable, [sys.executable, __file__, _VERIFY, *arguments])
    os.execvp(arguments[0], arguments)
    return 2


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except (OSError, RuntimeError) as error:
        print(f"test permissions: {error}", file=sys.stderr)
        raise SystemExit(2)
