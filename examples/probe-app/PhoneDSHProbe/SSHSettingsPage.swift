// SPDX-License-Identifier: MIT
import Foundation
import Observation
import PhoneDSH
import PhoneDSHSSH
import SwiftUI
import UniformTypeIdentifiers

struct SavedSSHConnection: Codable, Identifiable {
    var id = UUID().uuidString
    var alias = ""
    var host = ""
    var port = 22
    var username = ""
    var usePrivateKey = false
    var trustedHostKey = ""
}

private struct SSHSecrets: Codable {
    var password = ""
    var privateKey = ""
    var passphrase = ""
}

@MainActor @Observable final class SSHSettingsStore {
    static let shared = SSHSettingsStore()
    private(set) var connections: [SavedSSHConnection]
    private let defaultsKey = "phone-dsh.ssh.connections.v1"
    private let secrets = KeychainModelProviderSecretStore(service: "dev.phone-dsh.probe.ssh")

    init() {
        connections = UserDefaults.standard.data(forKey: defaultsKey)
            .flatMap { try? JSONDecoder().decode([SavedSSHConnection].self, from: $0) } ?? []
    }

    fileprivate func credentials(for connection: SavedSSHConnection) throws -> SSHSecrets {
        guard let secret = try secrets.secret(for: connection.id) else { return SSHSecrets() }
        return try JSONDecoder().decode(SSHSecrets.self, from: Data(secret.utf8))
    }

    fileprivate func save(_ connection: SavedSSHConnection, credentials: SSHSecrets) throws {
        guard connection.alias.range(of: #"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"#, options: .regularExpression) != nil,
              !connection.host.isEmpty, !connection.host.contains(where: { $0.isWhitespace }),
              !connection.username.isEmpty, (1...65535).contains(connection.port), !connection.trustedHostKey.isEmpty else {
            throw SSHFailure("请填写连接别名、地址、用户名，并确认服务器指纹。")
        }
        guard !connections.contains(where: { $0.id != connection.id && $0.alias == connection.alias }) else { throw SSHFailure("连接别名已存在。") }
        var next = connections.filter { $0.id != connection.id }
        next.append(connection)
        let data = try JSONEncoder().encode(next)
        try secrets.setSecret(String(decoding: JSONEncoder().encode(credentials), as: UTF8.self), for: connection.id)
        UserDefaults.standard.set(data, forKey: defaultsKey)
        connections = next
    }

    func remove(_ offsets: IndexSet) throws {
        var next = connections
        for index in offsets.sorted(by: >) {
            try secrets.deleteSecret(for: next[index].id)
            next.remove(at: index)
        }
        UserDefaults.standard.set(try JSONEncoder().encode(next), forKey: defaultsKey)
        connections = next
    }

    func list() -> String {
        if connections.isEmpty { return "No saved SSH connections. Add a connection in Settings → SSH." }
        return connections.map { "\($0.alias)\t\($0.username)@\($0.host):\($0.port)" }.joined(separator: "\n")
    }

    func resolve(_ destination: String) throws -> SSHConnection {
        let matches = connections.filter {
            $0.alias == destination || $0.host == destination || "\($0.username)@\($0.host)" == destination
                || "\($0.username)@[\($0.host)]" == destination || "[\($0.host)]" == destination
        }
        guard matches.count == 1, let profile = matches.first else { throw SSHFailure("Unknown or ambiguous SSH connection. Use ssh --list and a saved alias.") }
        let credential = try credentials(for: profile)
        return SSHConnection(host: profile.host, port: profile.port, username: profile.username,
            password: profile.usePrivateKey ? "" : credential.password,
            privateKey: profile.usePrivateKey ? credential.privateKey : "", passphrase: credential.passphrase,
            trustedHostKey: profile.trustedHostKey)
    }
}

struct SSHSettingsPage: View {
    @State private var store = SSHSettingsStore.shared
    @State private var error: String?
    var body: some View {
        List {
            Section {
                ForEach(store.connections) { connection in
                    NavigationLink { SSHConnectionEditor(connection: connection) } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(connection.alias)
                            Text("\(connection.username)@\(connection.host):\(connection.port)").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }.onDelete { offsets in
                    do { try store.remove(offsets) } catch { self.error = error.localizedDescription }
                }
                NavigationLink("添加连接") { SSHConnectionEditor(connection: SavedSSHConnection()) }
            } footer: {
                Text("聊天中使用 ssh 别名 命令，或 scp 本地文件 别名:远程路径。密码和私钥保存在钥匙串中。命令执行和文件传输由 iPhone 直接连接服务器。")
            }
            if let error { Text(error).foregroundStyle(.red) }
        }
        .navigationTitle("SSH")
    }
}

private struct SSHConnectionEditor: View {
    @State var connection: SavedSSHConnection
    @State private var credentials = SSHSecrets()
    @State private var error: String?
    @State private var pendingKey: String?
    @State private var confirmingKey = false
    @State private var busy = false
    @State private var importingKey = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section("连接") {
                TextField("别名，例如 my-server", text: $connection.alias)
                TextField("服务器地址", text: $connection.host)
                TextField("端口", value: $connection.port, format: .number.grouping(.never)).keyboardType(.numberPad)
                TextField("用户名", text: $connection.username)
            }.textInputAutocapitalization(.never).autocorrectionDisabled()
            Section("认证") {
                Toggle("使用私钥", isOn: $connection.usePrivateKey)
                if connection.usePrivateKey {
                    SecureField("OpenSSH 私钥（推荐 Ed25519）", text: $credentials.privateKey)
                    Button("从文件导入私钥") { importingKey = true }
                    SecureField("私钥口令（可选）", text: $credentials.passphrase)
                    Text("支持完整 OpenSSH 私钥，可直接粘贴；RSA 需服务器允许 ssh-rsa；加密密钥支持 AES-128/256-CTR，bcrypt 轮数须小于 18；不支持 PEM / ECDSA。")
                        .font(.caption).foregroundStyle(.secondary)
                } else { SecureField("密码", text: $credentials.password) }
            }.textInputAutocapitalization(.never).autocorrectionDisabled()
            Section("服务器指纹") {
                if !connection.trustedHostKey.isEmpty {
                    Text(SSHConnection.fingerprint(of: connection.trustedHostKey))
                        .font(.caption.monospaced()).textSelection(.enabled)
                }
                Button(busy ? "正在读取…" : "读取并核对指纹") { inspect() }.disabled(busy)
                Text("请与服务器管理员提供的指纹核对。保存后只接受此密钥，服务器密钥改变时连接会失败。")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let error { Text(error).foregroundStyle(.red) }
            Button("保存") {
                do {
                    guard connection.usePrivateKey ? !credentials.privateKey.isEmpty : !credentials.password.isEmpty else { throw SSHFailure("请填写密码或私钥。") }
                    var saved = credentials
                    if connection.usePrivateKey { saved.password = "" } else { saved.privateKey = ""; saved.passphrase = "" }
                    try SSHSettingsStore.shared.save(connection, credentials: saved)
                    dismiss()
                } catch { self.error = error.localizedDescription }
            }.disabled(busy || connection.trustedHostKey.isEmpty)
        }
        .navigationTitle(connection.alias.isEmpty ? "添加 SSH 连接" : connection.alias)
        .task {
            do { credentials = try SSHSettingsStore.shared.credentials(for: connection) }
            catch { self.error = error.localizedDescription }
        }
        .fileImporter(isPresented: $importingKey, allowedContentTypes: [.data]) { result in
            do {
                let url = try result.get()
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= 65_536 else { throw SSHFailure("私钥文件过大。") }
                credentials.privateKey = try String(contentsOf: url, encoding: .utf8)
            } catch { self.error = error.localizedDescription }
        }
        .onChange(of: connection.host) { _, _ in connection.trustedHostKey = ""; pendingKey = nil }
        .onChange(of: connection.port) { _, _ in connection.trustedHostKey = ""; pendingKey = nil }
        .alert("确认服务器指纹", isPresented: $confirmingKey) {
            Button("取消", role: .cancel) { pendingKey = nil }
            Button("确认并信任") { connection.trustedHostKey = pendingKey ?? ""; pendingKey = nil }
        } message: { Text(pendingKey.map(SSHConnection.fingerprint) ?? "") }
    }

    private func inspect() {
        busy = true; error = nil
        let host = connection.host, port = connection.port
        Task {
            defer { busy = false }
            do {
                let key = try await SSHConnection.inspectHostKey(host: host, port: port)
                guard connection.host == host, connection.port == port else { return }
                pendingKey = key; confirmingKey = true
            } catch { self.error = error.localizedDescription }
        }
    }
}
