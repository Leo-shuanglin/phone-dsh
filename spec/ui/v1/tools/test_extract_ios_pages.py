#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Fixture tests for SwiftUI page structure extraction."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import extract_ios_pages as extract  # noqa: E402


class ToolbarPickerTests(unittest.TestCase):
    def test_picker_in_toolbar_is_extracted_with_segment_options(self) -> None:
        swift = '''import SwiftUI
struct NativeWorkbenchPage: View {
    @State private var section = 0
    var body: some View {
        NavigationStack {
            Section("Controls") {
                Picker("普通选项", selection: $section) {
                    Text("普通值").tag(2)
                }
            }
                .toolbar {
                    ToolbarItem(placement: .principal) {
                        Picker("工作区", selection: $section) {
                            Text("文件").tag(0)
                            Text("命令").tag(1)
                        }
                        .pickerStyle(.segmented)
                    }
                }
        }
    }
}
'''
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "examples/probe-app/PhoneDSHProbe/NativeWorkbenchPage.swift"
            source_path.parent.mkdir(parents=True)
            source_path.write_text(swift, encoding="utf-8")
            source = extract.Source(source_path, root)
            page = extract.PageExtractor(source, "NativeWorkbenchPage").run(
                extract.page_structs(source))

        picker_actions = [action for action in page["actions"] if action["kind"] == "picker"]
        self.assertEqual(len(picker_actions), 2)
        ordinary = [action for action in picker_actions if action["label"] == "普通选项"]
        toolbar = [action for action in picker_actions if action["label"] == "工作区"]
        self.assertEqual(len(ordinary), 1, "ordinary body Picker must not be re-added by the toolbar scan")
        self.assertEqual(len(toolbar), 1)
        self.assertEqual(toolbar[0]["items"], ["工作区", "文件", "命令"])
        self.assertEqual(toolbar[0]["style"], "segmented")


if __name__ == "__main__":
    unittest.main()
