// SPDX-License-Identifier: MIT
import SwiftUI
import PhoneDSHADB

struct AndroidADBSettingsPage: View {
    @State private var enabled = false
    @State private var host = ""
    @State private var port = "5555"
    @State private var status = ""
    @State private var busy = false
    @State private var failed = false

    var body: some View {
        Form {
            Section {
                Toggle("允许控制 Android", isOn: $enabled)
                TextField("Android IP 地址或主机名", text: $host)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                TextField("ADB 端口", text: $port).keyboardType(.numberPad)
                Button("保存") { save() }.disabled(busy)
                Button(busy ? "正在连接…" : "测试连接") { check() }.disabled(busy || !enabled)
                Button("停止当前 Android 操作", role: .cancel) { Task { await ADBMCPServer.shared.cancel() } }
            } footer: {
                Text("保存后立即生效。模型可控制屏幕、查询和管理应用、安装或卸载 APK、读取日志和执行 Android shell。首次连接请解锁 Android，并允许这台 iPhone 的调试密钥。")
            }
            if !status.isEmpty {
                Section("连接状态") {
                    Text(status).foregroundStyle(failed ? .red : .primary).textSelection(.enabled)
                }
            }
            Section("连接准备") {
                Text("两台手机连接同一局域网，在 Android 上开启开发者选项和 USB 调试。")
                Text("当前支持 TCP/IP ADB。首次可用电脑通过 USB 执行 adb tcpip 5555，再填入 Android 的 IP 地址。部分设备可直接开启网络 ADB；设备重启后可能需要重新开启。")
                Text("Android 11+「无线调试」里的六位配对码和 TLS 端口暂不支持。这里填写 TCP/IP ADB 端口，通常为 5555。")
                Text("文本输入支持可打印 ASCII。中文等 Unicode 输入需要 Android 输入法支持，当前会明确报错。")
            }
            Section("应用与 APK") {
                Text("可以说：列出 Android 上的第三方应用；查看某个应用的版本和权限；安装工作区里的 APK；卸载指定包名的应用。")
                Text("支持工作区或 Android 本机上的单个 APK。工作区安装上限 128 MiB；暂不支持 AAB、XAPK、APKS 和拆分 APK 组合。")
                Text("卸载应用和删除下载的 APK 文件是两种操作。应用管理默认作用于 Android 当前用户；清除应用数据会删除登录状态和本地数据。")
            }
        }
        .navigationTitle("Android ADB")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            let saved = ADBSettings.shared.load()
            enabled = saved.enabled; host = saved.host; port = String(saved.port)
        }
    }

    private func configuration() throws -> ADBConfiguration {
        guard let number = UInt16(port), number > 0 else { throw ADBFailure("端口必须是 1–65535") }
        return ADBConfiguration(enabled: enabled, host: host.trimmingCharacters(in: .whitespacesAndNewlines), port: number)
    }
    private func save() {
        busy = true
        Task {
            defer { busy = false }
            do { try await ADBMCPServer.shared.configure(configuration()); status = "已保存。"; failed = false }
            catch { status = error.localizedDescription; failed = true }
        }
    }
    private func check() {
        busy = true; failed = false; status = "请查看 Android 上的调试授权提示。"
        Task {
            defer { busy = false }
            do {
                status = try await ADBMCPServer.shared.checkConnection(configuration())
                failed = false
            } catch { status = error.localizedDescription; failed = true }
        }
    }
}
