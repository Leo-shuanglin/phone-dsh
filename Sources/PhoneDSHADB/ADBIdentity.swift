// SPDX-License-Identifier: MIT
import Foundation
import Security

/// The device's ADB private key stays in Keychain and never enters the Worker.
public final class ADBIdentity: @unchecked Sendable {
    private let key: SecKey
    private static let lock = NSLock()
    private static let tag = Data("phone-dsh.android-adb.rsa.v1".utf8)

    init(key: SecKey) { self.key = key }

    public static func loadOrCreate() throws -> ADBIdentity {
        try lock.withLock {
            var item: CFTypeRef?
            let status = SecItemCopyMatching([
                kSecClass: kSecClassKey, kSecAttrApplicationTag: tag,
                kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecReturnRef: true,
            ] as CFDictionary, &item)
            if status == errSecSuccess { return ADBIdentity(key: item as! SecKey) }
            guard status == errSecItemNotFound else { throw ADBFailure("Cannot read ADB Keychain identity (\(status))") }
            var error: Unmanaged<CFError>?
            guard let key = SecKeyCreateRandomKey([
                kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits: 2048,
                kSecPrivateKeyAttrs: [kSecAttrIsPermanent: true, kSecAttrApplicationTag: tag,
                                     kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly],
            ] as CFDictionary, &error) else {
                throw ADBFailure("Cannot create ADB Keychain identity: \(error?.takeRetainedValue().localizedDescription ?? "unknown error")")
            }
            return ADBIdentity(key: key)
        }
    }

    func sign(_ token: Data) throws -> Data {
        guard token.count == 20 else { throw ADBFailure("Invalid ADB authentication challenge") }
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(key, .rsaSignatureDigestPKCS1v15SHA1, token as CFData, &error) else {
            throw ADBFailure("Cannot sign ADB authentication challenge")
        }
        return signature as Data
    }

    func publicKey() throws -> Data {
        guard let publicKey = SecKeyCopyPublicKey(key),
              let der = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else {
            throw ADBFailure("Cannot export ADB public key")
        }
        return Data((try Self.androidPublicKey(der).base64EncodedString() + " phone-dsh@iphone\0").utf8)
    }

    /// Encode PKCS#1 RSA public DER as Android's 524-byte little-endian key.
    /// Montgomery parameters are computed here without another crypto library.
    static func androidPublicKey(_ der: Data) throws -> Data {
        let bytes = Array(der)
        var offset = 0
        func read(_ tag: UInt8) throws -> [UInt8] {
            guard offset + 2 <= bytes.count, bytes[offset] == tag else { throw ADBFailure("Invalid RSA DER") }
            offset += 1
            var count = Int(bytes[offset]); offset += 1
            if count & 128 != 0 {
                let length = count & 127
                guard (1...4).contains(length), offset + length <= bytes.count else { throw ADBFailure("Invalid RSA DER length") }
                count = 0
                for _ in 0..<length { count = count * 256 + Int(bytes[offset]); offset += 1 }
            }
            guard count <= bytes.count - offset else { throw ADBFailure("Truncated RSA DER") }
            if tag == 0x30 { return [] }
            let result = Array(bytes[offset..<offset + count]); offset += count
            return result
        }
        _ = try read(0x30)
        var modulus = try read(0x02)
        while modulus.first == 0 { modulus.removeFirst() }
        let exponent = try read(0x02)
        guard modulus.count == 256, modulus[0] & 128 != 0, modulus[255] & 1 == 1,
              exponent == [1, 0, 1] else { throw ADBFailure("ADB requires a 2048-bit RSA key with exponent 65537") }
        modulus.reverse()
        let low = Data(modulus).le32(0)
        var inverse: UInt32 = 1
        for _ in 0..<5 { inverse = inverse &* (2 &- low &* inverse) }
        var rr = [UInt8](repeating: 0, count: 256); rr[0] = 1
        for _ in 0..<4096 {
            var carry = 0
            for i in 0..<256 { let value = Int(rr[i]) * 2 + carry; rr[i] = UInt8(value & 255); carry = value >> 8 }
            if carry != 0 || !rr.reversed().lexicographicallyPrecedes(modulus.reversed()) {
                var borrow = 0
                for i in 0..<256 {
                    let value = Int(rr[i]) - Int(modulus[i]) - borrow
                    rr[i] = UInt8(truncatingIfNeeded: value); borrow = value < 0 ? 1 : 0
                }
            }
        }
        var result = Data(); result.appendLE(64); result.appendLE(0 &- inverse)
        result.append(contentsOf: modulus); result.append(contentsOf: rr); result.appendLE(65537)
        return result
    }
}
