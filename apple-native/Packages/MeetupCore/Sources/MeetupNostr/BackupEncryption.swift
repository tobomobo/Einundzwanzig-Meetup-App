import Foundation
import CryptoSwift
#if canImport(CommonCrypto)
import CommonCrypto
#endif

/// Byte-compatible with Flutter backup_service.dart: UTF-8 password, no Unicode
/// normalization, PBKDF2-HMAC-SHA256(600000), AES-256-GCM, ciphertext || tag.
public enum BackupEncryption {
    public static func encrypt(_ data: Data, password: String) throws -> String {
        try encrypt(data, password: password, salt: IdentityRandom.bytes(32), iv: IdentityRandom.bytes(16))
    }
    static func encrypt(_ data: Data, password: String, salt: Data, iv: Data) throws -> String {
        guard !password.isEmpty, data.count <= 8 * 1024 * 1024, salt.count == 32, iv.count == 16 else { throw IdentityError.invalidBackup }
        var key = try derive(password: password, salt: salt)
        defer { key.resetBytes(in: 0..<key.count) }
        let box = try AES(key: Array(key), blockMode: GCM(iv: Array(iv), mode: .combined), padding: .noPadding).encrypt(Array(data))
        return "enc_v2:" + salt.base64EncodedString() + ":" + iv.base64EncodedString() + ":" + Data(box).base64EncodedString()
    }
    public static func decrypt(_ payload: String, password: String) throws -> Data {
        guard payload.utf8.count <= 12 * 1024 * 1024 else { throw IdentityError.resourceLimit }
        let parts = payload.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ":", omittingEmptySubsequences: false)
        guard parts.first == "enc_v2" else { throw IdentityError.unsupportedVersion }
        guard parts.count == 4, let salt = Data(base64Encoded: String(parts[1])), salt.count == 32,
              let iv = Data(base64Encoded: String(parts[2])), iv.count == 16,
              let sealed = Data(base64Encoded: String(parts[3])), (16...(8 * 1024 * 1024 + 16)).contains(sealed.count)
        else { throw IdentityError.invalidBackup }
        var key = try derive(password: password, salt: salt)
        defer { key.resetBytes(in: 0..<key.count) }
        do { return Data(try AES(key: Array(key), blockMode: GCM(iv: Array(iv), mode: .combined), padding: .noPadding).decrypt(Array(sealed))) }
        catch { throw IdentityError.authenticationFailed }
    }
    private static func derive(password: String, salt: Data) throws -> Data {
        #if canImport(CommonCrypto)
        var key = Data(repeating: 0, count: 32)
        let passwordBytes = Array(password.utf8)
        let status = key.withUnsafeMutableBytes { out in
            salt.withUnsafeBytes { saltBuffer in
                passwordBytes.withUnsafeBytes { pass in
                    CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), pass.baseAddress?.assumingMemoryBound(to: CChar.self), passwordBytes.count,
                        saltBuffer.baseAddress?.assumingMemoryBound(to: UInt8.self), salt.count, CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        600_000, out.baseAddress?.assumingMemoryBound(to: UInt8.self), 32)
                }
            }
        }
        guard status == kCCSuccess else { throw IdentityError.authenticationFailed }
        return key
        #else
        return Data(try PKCS5.PBKDF2(password: Array(password.utf8), salt: Array(salt), iterations: 600_000, keyLength: 32, variant: .sha2(.sha256)).calculate())
        #endif
    }
}
