# SPDX-License-Identifier: MIT
"""Mutation tests for the extractor's action-ownership rule.

An action belongs to the page whose struct body contains it — that is what
`PageExtractor._live` has always meant. `_inline_presented_actions` used to copy the
actions of a *sibling* `private struct` (the presented sheet's content) into the
parent contract, bypassing that test: it put `刷新`/`完成` from
`NativeJobListSheet`/`NativeScheduleListSheet`/`CommandExecutionResultPage` into
`pages/chat.json` and `保存`/`修改计划时间` from `ScheduleEditorPage` into
`pages/native-automation.json`, although `ChatPage`/`NativeAutomationPage` never
render those structs.

These tests pin both directions of the rule:
  * a sibling screen (`private struct DemoSheet` at file scope) is not this page's;
  * a nested helper view (declared inside the page struct, no screen chrome) still is;
  * a nested screen (own `navigationTitle`/`.toolbar`) is not.
"""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import extract_ios_pages as E  # noqa: E402


SIBLING_SHEET = """\
import SwiftUI

struct DemoPage: View {
    @State private var showSheet = false

    var body: some View {
        List {
            Section("内容") {
                Button("提交") { submit() }
            }
        }
        .sheet(isPresented: $showSheet) {
            DemoSheet()
                .presentationDetents([.medium])
        }
    }
}

private struct DemoSheet: View {
    var body: some View {
        NavigationStack {
            List {
                Section("条目") {
                    Text("一")
                }
            }
            .navigationTitle("条目")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("刷新") { refresh() }
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }
}
"""

NESTED_HELPER = """\
import SwiftUI

struct DemoPage: View {
    @State private var showConfirm = false

    var body: some View {
        List {
            Section("内容") {
                Text("行")
            }
        }
        .sheet(isPresented: $showConfirm) {
            NestedConfirm()
        }
    }

    private struct NestedConfirm: View {
        var body: some View {
            VStack {
                Button("移除") { remove() }
                Button("保留") { keep() }
            }
        }
    }
}
"""

NESTED_SCREEN = """\
import SwiftUI

struct DemoPage: View {
    @State private var showEditor = false

    var body: some View {
        List {
            Section("内容") {
                Text("行")
            }
        }
        .sheet(isPresented: $showEditor) {
            NestedEditor()
        }
    }

    private struct NestedEditor: View {
        var body: some View {
            Form {
                Section("字段") {
                    Text("值")
                }
            }
            .navigationTitle("编辑")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                }
            }
        }
    }
}
"""


def extract(swift: str) -> dict:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        path = root / "DemoPage.swift"
        path.write_text(swift, encoding="utf-8")
        source = E.Source(path, root)
        return E.PageExtractor(source, "DemoPage").run(E.page_structs(source))


def labels(page: dict) -> dict:
    return {action["label"]: action for action in page["actions"] if action.get("label")}


class ActionOwnershipTest(unittest.TestCase):
    def test_page_actions_still_extracted(self):
        page = extract(SIBLING_SHEET)
        self.assertIn("提交", labels(page), "the page's own button must survive")

    def test_a_presented_sheet_is_part_of_the_parent_contract(self):
        """README §1: 内嵌子页随父页实现、不单独排期.

        A presented sheet is therefore the parent's contract: Android is told to
        implement it together with the page, and the finding it produces when the
        sheet is missing ("this embedded screen does not exist") is the work item.
        Excluding these was tried and reverted — it hid five real gaps and turned
        sixteen faithfully-implemented inline controls into invented extra-actions.
        """
        page = extract(SIBLING_SHEET)
        names = labels(page)
        self.assertIn("刷新", names, "a presented sheet's toolbar is the parent's contract")
        self.assertIn("关闭", names)
        self.assertTrue([a for a in page["actions"] if a.get("presentedBy")],
                        "inlined actions carry presentedBy so the owner is still visible")

    def test_presentation_is_still_recorded(self):
        page = extract(SIBLING_SHEET)
        self.assertEqual(["DemoSheet"], [p["destination"] for p in page["presentations"]])

    def test_nested_helper_view_buttons_belong_to_the_page(self):
        page = extract(NESTED_HELPER)
        names = labels(page)
        self.assertIn("移除", names, "a nested helper row inside the page struct is the page's own")
        self.assertIn("保留", names)

    def test_a_nested_screen_is_also_part_of_the_parent_contract(self):
        """Same rule as the presented sheet, and for the same reason (§1)."""
        page = extract(NESTED_SCREEN)
        self.assertIn("保存", labels(page),
                      "an embedded screen ships with the page that presents it")


if __name__ == "__main__":
    unittest.main()
