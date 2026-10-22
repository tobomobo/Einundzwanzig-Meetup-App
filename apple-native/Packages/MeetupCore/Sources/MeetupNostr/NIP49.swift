import Foundation
import CryptoSwift

public enum NIP49 {
    public enum KeySecurity: UInt8, Sendable { case insecure = 0, secure = 1, unknown = 2 }
    public static let defaultLogN = 16
    // A hostile header must not force multi-gigabyte allocations. 18 supports
    // 256 MiB; higher costs require an explicitly provisioned desktop workflow.
    public static let maximumLogN = 18
    public static func encrypt(_ privateKey: Data, password: String, logN: Int = defaultLogN,
                               security: KeySecurity = .unknown) throws -> String {
        try encrypt(privateKey, password: password, logN: logN, security: security,
                    salt: IdentityRandom.bytes(16), nonce: IdentityRandom.bytes(24))
    }
    static func encrypt(_ privateKey: Data, password: String, logN: Int, security: KeySecurity,
                        salt: Data, nonce: Data) throws -> String {
        try IdentityKey.validate(privateKey)
        guard !password.isEmpty, salt.count == 16, nonce.count == 24 else { throw IdentityError.invalidEncoding }
        var key = try derive(password: password, salt: salt, logN: logN)
        defer { key.resetBytes(in: 0..<key.count) }
        let box = try AEADXChaCha20Poly1305.encrypt(Array(privateKey), key: Array(key), iv: Array(nonce), authenticationHeader: [security.rawValue])
        let payload = Data([2, UInt8(logN)]) + salt + nonce + Data([security.rawValue]) + Data(box.cipherText) + Data(box.authenticationTag)
        return NIP19.encode(prefix: "ncryptsec", bytes: payload)
    }
    public static func decrypt(_ encoded: String, password: String) throws -> Data {
        let data = try NIP19.decode(encoded, prefix: "ncryptsec", count: 91)
        guard data[0] == 2 else { throw IdentityError.unsupportedVersion }
        guard KeySecurity(rawValue: data[42]) != nil else { throw IdentityError.invalidEncoding }
        var key = try derive(password: password, salt: Data(data[2..<18]), logN: Int(data[1]))
        defer { key.resetBytes(in: 0..<key.count) }
        do {
            let box = try AEADXChaCha20Poly1305.decrypt(Array(data[43..<75]), key: Array(key), iv: Array(data[18..<42]),
                authenticationHeader: [data[42]], authenticationTag: Array(data[75..<91]))
            guard box.success else { throw IdentityError.authenticationFailed }
            let secret = Data(box.plainText)
            try IdentityKey.validate(secret)
            return secret
        } catch { throw IdentityError.authenticationFailed }
    }
    private static func derive(password: String, salt: Data, logN: Int) throws -> Data {
        guard (1...maximumLogN).contains(logN) else { throw IdentityError.resourceLimit }
        let normalized = password.precomposedStringWithCompatibilityMapping
        return Data(try Scrypt(password: Array(normalized.utf8), salt: Array(salt), dkLen: 32, N: 1 << logN, r: 8, p: 1).calculate())
    }
}
