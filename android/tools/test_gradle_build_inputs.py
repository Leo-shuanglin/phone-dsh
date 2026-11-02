#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Task-graph checks for the PDSH Android build inputs.

The bug this test locks down is a *graph* bug, not a string typo: `:app` used to
run `check(...)` for the 65 MB Worker bundle and the Android `.so` at
configuration time, so `:app:testDebugUnitTest` could not even configure on a
host without those device inputs. `:testkit` attached the same staging to
`preBuild`, which is a parent of its JVM tests. The checks below ask Gradle for
the real task graph (`--dry-run` plus an init script) and assert the actual
model relationships:

  * pure JVM test graphs contain no APK/device staging tasks;
  * `:app:assembleDebug` / `:app:assembleRelease` contain the per-variant
    `package<PdshJni>` tasks and `:engine:packageWorkerBundle`;
  * `:testkit:assembleDebugAndroidTest` contains the Worker/scenario/JNI
    staging tasks.

`--dry-run` still configures the projects, so a configuration-time `check(...)`
would fail here exactly as it did for callers. The real packaging tasks keep
their fail-loud `doFirst`/action checks; this test does not execute them (the
Linux host may not have the NDK output or the 65 MB Worker image), and the Mac
owner runs the full production APK assembly.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ANDROID_DIR = REPO_ROOT / "android"
WRAPPER = REPO_ROOT / "android" / "tools" / "with-android-toolchain.sh"
GRADLEW = ANDROID_DIR / "gradlew"

# The Graph is populated after configuration; writing it from whenReady also
# proves configuration completed without the missing device inputs.
INIT_SCRIPT = r"""
gradle.taskGraph.whenReady { graph ->
    def out = new File(System.getenv("PDSH_TASK_GRAPH_OUT"))
    def lines = graph.allTasks.collect { it.path }
    out.text = lines.sort().join("\n") + "\n"
}
"""


class GradleBuildInputTaskGraphTests(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls):
        if not GRADLEW.is_file():
            raise unittest.SkipTest("android/gradlew is absent")
        if not WRAPPER.is_file():
            raise unittest.SkipTest("with-android-toolchain.sh is absent")
        if shutil.which("bash") is None:
            raise unittest.SkipTest("bash is absent")

    def task_graph(self, tasks):
        """Return the configured task-path set for one Gradle invocation."""
        with tempfile.TemporaryDirectory() as temp:
            temp_dir = Path(temp)
            init = temp_dir / "capture-task-graph.gradle"
            out = temp_dir / "tasks.txt"
            init.write_text(INIT_SCRIPT, encoding="utf-8")
            env = os.environ.copy()
            env["PDSH_TASK_GRAPH_OUT"] = str(out)
            command = [
                "bash",
                str(WRAPPER),
                "./gradlew",
                "--configure-on-demand",
                "--no-daemon",
                "--quiet",
                "-I",
                str(init),
                *tasks,
                "--dry-run",
            ]
            proc = subprocess.run(
                command,
                cwd=str(REPO_ROOT),
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                universal_newlines=True,
                timeout=1800,
                check=False,
            )
            if proc.returncode != 0:
                self.fail(
                    "Gradle task-graph invocation failed (exit {}):\n{}\n{}".format(
                        proc.returncode, " ".join(command), proc.stdout[-12000:]
                    )
                )
            if not out.is_file():
                self.fail(
                    "Gradle did not populate the task graph (init script was not run):\n"
                    + proc.stdout[-12000:]
                )
            return {
                line.strip()
                for line in out.read_text(encoding="utf-8").splitlines()
                if line.strip()
            }

    def test_jvm_tests_do_not_require_apk_or_device_inputs(self):
        graph = self.task_graph(
            [
                ":app:testDebugUnitTest",
                ":lib:testDebugUnitTest",
                ":testkit:testDebugUnitTest",
            ]
        )
        requested = {
            ":app:testDebugUnitTest",
            ":lib:testDebugUnitTest",
            ":testkit:testDebugUnitTest",
        }
        self.assertTrue(requested.issubset(graph), sorted(requested - graph))

        forbidden = {
            ":app:packageDebugPdshJni",
            ":engine:packageWorkerBundle",
            ":engine:verifyWorkerBundle",
            ":testkit:packageWorkerBundleForTests",
            ":testkit:packageScenarioCatalogForTests",
            ":testkit:stageAndroidTestJni",
            ":testkit:verifyWorkerBundleForTests",
            ":testkit:verifyAndroidTestJni",
            ":testkit:verifyScenarioCatalog",
        }
        self.assertFalse(
            forbidden & graph,
            "pure JVM test graph must not require APK/device inputs: {}".format(
                sorted(forbidden & graph)
            ),
        )
        android_test_staging = {
            task
            for task in graph
            if "AndroidTestAssets" in task
            or "AndroidTestJni" in task
            or "AndroidTestNative" in task
        }
        self.assertFalse(
            android_test_staging,
            "pure JVM test graph must not merge Android-test staging: {}".format(
                sorted(android_test_staging)
            ),
        )

    def test_debug_and_release_apk_packaging_use_real_jni_and_worker_inputs(self):
        graph = self.task_graph([":app:assembleDebug", ":app:assembleRelease"])
        expected = {
            ":app:packageDebugPdshJni",
            ":app:packageReleasePdshJni",
            ":engine:packageWorkerBundle",
            ":engine:verifyWorkerBundle",
        }
        self.assertTrue(
            expected.issubset(graph),
            "APK packaging must depend on the real inputs; missing: {}".format(
                sorted(expected - graph)
            ),
        )
        # These are real consumers, not generated dummy outputs.
        self.assertIn(":app:mergeDebugJniLibFolders", graph)
        self.assertIn(":app:mergeReleaseJniLibFolders", graph)

    def test_testkit_instrumented_packaging_keeps_device_staging(self):
        graph = self.task_graph([":testkit:assembleDebugAndroidTest"])
        expected = {
            ":testkit:packageWorkerBundleForTests",
            ":testkit:packageScenarioCatalogForTests",
            ":testkit:stageAndroidTestJni",
            ":testkit:verifyWorkerBundleForTests",
            ":testkit:verifyAndroidTestJni",
            ":testkit:verifyScenarioCatalog",
        }
        self.assertTrue(
            expected.issubset(graph),
            "instrumented test APK must stage real device inputs; missing: {}".format(
                sorted(expected - graph)
            ),
        )
        # The merge tasks are the packaging consumers, not preBuild.
        self.assertTrue(
            any(task.endswith("AndroidTestAssets") for task in graph),
            sorted(graph),
        )
        self.assertTrue(
            any("AndroidTestJni" in task or "AndroidTestNative" in task for task in graph),
            sorted(graph),
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
