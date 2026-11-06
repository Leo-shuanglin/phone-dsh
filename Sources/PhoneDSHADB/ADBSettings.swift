// SPDX-License-Identifier: MIT
import Foundation

public struct ADBConfiguration: Codable, Equatable, Sendable {
    public var enabled: Bool
    public var host: String
    public var port: UInt16
    public init(enabled: Bool = false, host: String = "", port: UInt16 = 5555) {
        self.enabled = enabled; self.host = host; self.port = port
    }
    public func endpoint() throws -> ADBEndpoint {
        guard enabled else { throw ADBFailure("Android control is disabled. Call android_connect with the user-provided host and port to enable it.") }
        return try ADBEndpoint(host: host, port: port)
    }
}

public final class ADBSettings: @unchecked Sendable {
    public static let shared = ADBSettings()
    private let defaults: UserDefaults
    private let lock = NSLock()
    private let key = "phone-dsh.android-adb.configuration.v1"
    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    public func load() -> ADBConfiguration {
        lock.withLock {
            guard let data = defaults.data(forKey: key), let value = try? JSONDecoder().decode(ADBConfiguration.self, from: data)
            else { return ADBConfiguration() }
            return value
        }
    }
    public func save(_ configuration: ADBConfiguration) throws {
        if configuration.enabled { _ = try configuration.endpoint() }
        let data = try JSONEncoder().encode(configuration)
        lock.withLock { defaults.set(data, forKey: key) }
    }
}
