// SPDX-License-Identifier: MIT
import SwiftUI

/// Compact presentation of DSH's right sidebar. This is scoped to the current
/// conversation rather than a top-level navigation destination.
struct NativeWorkbenchPage: View {
    let sessionID: String?
    var initialPath: String? = nil
    var onClose: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var section = 0

    var body: some View {
        NavigationStack {
            Group {
                if section == 0 {
                    FilesPage(initialPath: initialPath)
                } else {
                    PDSHShellPage()
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // The two panes switch in the bar itself, the way phone apps
                // put a segmented title in the middle of the navigation bar.
                ToolbarItem(placement: .principal) {
                    Picker("工作区", selection: $section) {
                        Text("文件").tag(0)
                        Text("命令").tag(1)
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 180)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { onClose?(); dismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.large])
        .homeSheetChrome()
    }
}
