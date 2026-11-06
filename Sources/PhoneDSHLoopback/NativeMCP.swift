// SPDX-License-Identifier: MIT
import Foundation

/// An in-process MCP endpoint on the existing authenticated loopback origin.
/// Implementations never bind another listener or receive unauthenticated requests.
public protocol NativeMCPServing: AnyObject, Sendable {
    func handle(_ request: LoopbackRequest) async -> NativeMCPResponse
    func cancelPendingOperations() async
}

public extension NativeMCPServing {
    func cancelPendingOperations() async {}
}

public struct NativeMCPResponse: Sendable {
    public var status: Int
    public var body: Data
    public init(status: Int = 200, body: Data = Data()) {
        self.status = status
        self.body = body
    }
}
