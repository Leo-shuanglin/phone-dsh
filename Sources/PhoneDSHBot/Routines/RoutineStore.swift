// SPDX-License-Identifier: MIT

import Foundation

public protocol RoutineStore: Sendable {
    func read() throws -> RoutineDocument
    func write(_ document: RoutineDocument) throws
}

public final class JSONRoutineStore: RoutineStore, @unchecked Sendable {
    public let fileURL: URL
    private let fileManager: FileManager
    private let lock = NSLock()

    public init(fileURL: URL, fileManager: FileManager = .default) {
        self.fileURL = fileURL
        self.fileManager = fileManager
    }

    public func read() throws -> RoutineDocument {
        try lock.withLock {
            guard fileManager.fileExists(atPath: fileURL.path) else { return RoutineDocument() }
            let data = try Data(contentsOf: fileURL)
            if data.isEmpty { return RoutineDocument() }
            let document = try JSONDecoder().decode(RoutineDocument.self, from: data)
            guard document.version == 1 else { throw RoutineError.invalidRoutine("unsupported routine document version: \(document.version)") }
            return document
        }
    }

    public func write(_ document: RoutineDocument) throws {
        try lock.withLock {
            try fileManager.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            let data = try encoder.encode(document)
            try data.write(to: fileURL, options: .atomic)
        }
    }
}
