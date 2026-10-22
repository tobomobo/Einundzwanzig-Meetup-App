import Foundation
import P256K
import CryptoSwift

/// NIP-44 v2 framing and KDF. Cryptographic primitives are supplied by pinned
/// swift-secp256k1 and CryptoSwift, never implemented as app-level arithmetic.
public enum NIP44 {
    public static func conversationKey(privateKey: Data, publicKey: String) throws -> Data {
        try IdentityKey.validate(privateKey)
        let peer = try NostrHex.decode(publicKey, byteCount: 32, field: "public key")
        // Parsing the compressed even-Y point rejects off-curve/twist inputs.
        let point = try P256K.KeyAgreement.PublicKey(dataRepresentation: Data([2]) + peer)
        let key = try P256K.KeyAgreement.PrivateKey(dataRepresentation: privateKey)
        let shared = key.sharedSecretFromKeyAgreement(with: point, format: .compressed)
        let x = shared.withUnsafeBytes { Array($0.dropFirst()) }
        return Data(try mac(key: Array("nip44-v2".utf8), data: x))
    }
    public static func encrypt(_ plaintext: String, conversationKey: Data) throws -> String {
        try encrypt(plaintext, conversationKey: conversationKey, nonce: IdentityRandom.bytes(32))
    }
    // Deterministic nonce is deliberately internal, only exposed to vector tests.
    static func encrypt(_ plaintext: String, conversationKey: Data, nonce: Data) throws -> String {
        let plain = Array(plaintext.utf8)
        guard (1...65_535).contains(plain.count) else { throw IdentityError.resourceLimit }
        let keys = try messageKeys(conversationKey, nonce: nonce)
        let padded = [UInt8(plain.count >> 8), UInt8(plain.count & 255)] + plain +
            Array(repeating: UInt8(0), count: paddedLength(plain.count) - plain.count)
        let ciphertext = try ChaCha20(key: keys.chacha, iv: keys.nonce).encrypt(padded)
        let tag = try mac(key: keys.hmac, data: Array(nonce) + ciphertext)
        return (Data([2]) + nonce + Data(ciphertext) + Data(tag)).base64EncodedString()
    }
    public static func decrypt(_ payload: String, conversationKey: Data) throws -> String {
        guard (132...87_472).contains(payload.utf8.count), !payload.hasPrefix("#"),
              let data = Data(base64Encoded: payload), (99...65_603).contains(data.count)
        else { throw IdentityError.invalidEncoding }
        guard data.first == 2 else { throw IdentityError.unsupportedVersion }
        let nonce = Data(data[1..<33]), ciphertext = Array(data[33..<(data.count - 32)])
        let keys = try messageKeys(conversationKey, nonce: nonce)
        let expected = try mac(key: keys.hmac, data: Array(nonce) + ciphertext)
        guard equal(expected, Array(data.suffix(32))) else { throw IdentityError.authenticationFailed }
        let padded = try ChaCha20(key: keys.chacha, iv: keys.nonce).decrypt(ciphertext)
        guard padded.count >= 2 else { throw IdentityError.invalidEncoding }
        let length = Int(padded[0]) << 8 | Int(padded[1])
        guard (1...65_535).contains(length), padded.count == 2 + paddedLength(length), length <= padded.count - 2,
              let text = String(bytes: padded[2..<(2 + length)], encoding: .utf8)
        else { throw IdentityError.invalidEncoding }
        return text
    }
    static func paddedLength(_ length: Int) -> Int {
        if length <= 32 { return 32 }
        var next = 1
        while next < length { next <<= 1 }
        let chunk = next <= 256 ? 32 : next / 8
        return chunk * ((length - 1) / chunk + 1)
    }
    static func messageKeys(_ conversationKey: Data, nonce: Data) throws -> (chacha: [UInt8], nonce: [UInt8], hmac: [UInt8]) {
        guard conversationKey.count == 32, nonce.count == 32 else { throw IdentityError.invalidEncoding }
        // RFC 5869 Expand only: conversationKey is already the extracted PRK.
        var out: [UInt8] = [], previous: [UInt8] = []
        for i in 1...3 {
            previous = try mac(key: Array(conversationKey), data: previous + Array(nonce) + [UInt8(i)])
            out += previous
        }
        return (Array(out[0..<32]), Array(out[32..<44]), Array(out[44..<76]))
    }
    private static func mac(key: [UInt8], data: [UInt8]) throws -> [UInt8] {
        try CryptoSwift.HMAC(key: key, variant: .sha2(.sha256)).authenticate(data)
    }
    private static func equal(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for (x, y) in zip(a, b) { diff |= x ^ y }
        return diff == 0
    }
}
