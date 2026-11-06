// SPDX-License-Identifier: MIT
import Foundation
import Network

protocol ADBTransport: Sendable {
    func start() async throws
    func read(count: Int) async throws -> Data
    func write(_ data: Data) async throws
    func close()
}

final class ADBTCPTransport: ADBTransport, @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "phone-dsh.adb")
    private let lock = NSLock()
    private var startup: CheckedContinuation<Void, Error>?

    init(host: String, port: UInt16) {
        connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
    }

    func start() async throws {
        try Task.checkCancellation()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.withLock { startup = continuation }
                connection.stateUpdateHandler = { [weak self] state in
                    switch state {
                    case .ready: self?.finishStart(nil)
                    case .failed(let error): self?.finishStart(error)
                    case .cancelled: self?.finishStart(ADBFailure("ADB connection cancelled or timed out"))
                    default: break
                    }
                }
                connection.start(queue: queue)
            }
        } onCancel: { self.close() }
    }

    private func finishStart(_ error: Error?) {
        let pending = lock.withLock { let value = startup; startup = nil; return value }
        if let error { pending?.resume(throwing: error) } else { pending?.resume() }
    }

    func read(count: Int) async throws -> Data {
        var output = Data()
        while output.count < count {
            try Task.checkCancellation()
            let remaining = count - output.count
            let part: Data = try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { continuation in
                    connection.receive(minimumIncompleteLength: 1, maximumLength: remaining) { data, _, _, error in
                        if let data, !data.isEmpty { continuation.resume(returning: data) }
                        else { continuation.resume(throwing: error ?? ADBFailure("ADB connection closed; check authorization and TCP/IP debugging")) }
                    }
                }
            } onCancel: { self.close() }
            output.append(part)
        }
        return output
    }

    func write(_ data: Data) async throws {
        try Task.checkCancellation()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                connection.send(content: data, completion: .contentProcessed { error in
                    if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                })
            }
        } onCancel: { self.close() }
    }

    func close() { connection.cancel() }
}
