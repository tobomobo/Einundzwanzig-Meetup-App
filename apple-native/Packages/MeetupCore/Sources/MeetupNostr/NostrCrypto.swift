import Foundation
import P256K

/// Ephemeral cryptographic primitives. Non-Sendable provider keys/signatures
/// stay within this actor and only immutable Sendable values leave it. This is
/// not a key store and makes no promise of erasing copies of caller-owned Data.
public actor NostrCrypto {
    public init() {}

    public func derivePublicKey(privateKey: Data) throws -> Data {
        Data(try makePrivateKey(privateKey).xonly.bytes)
    }

    /// Signs an already-computed 32-byte event ID, without hashing it again.
    /// The provider's digest overload generates fresh 32-byte CSPRNG auxiliary data.
    public func signEventID(_ eventID: Data, privateKey: Data) throws -> Data {
        try requireLength(eventID, count: 32, field: "event ID")
        let key = try makePrivateKey(privateKey)
        do {
            return try key.signature(for: HashDigest(Array(eventID))).dataRepresentation
        } catch {
            throw NostrCryptoError.signingFailed
        }
    }

    /// Malformed inputs return false before constructing any provider wrapper.
    public func verifyEventID(_ eventID: Data, publicKey: Data, signature: Data) -> Bool {
        guard eventID.count == 32, publicKey.count == 32, signature.count == 64,
              let parsed = try? P256K.Schnorr.SchnorrSignature(dataRepresentation: signature) else { return false }
        let key = P256K.Schnorr.XonlyKey(dataRepresentation: Array(publicKey))
        // `parsed` remains private: its provider dataRepresentation is mutable.
        return key.isValidSignature(parsed, for: HashDigest(Array(eventID)))
    }

    public func sign(_ unsigned: UnsignedNostrEvent, privateKey: Data) throws -> NostrEvent {
        let key = try makePrivateKey(privateKey)
        guard NostrHex.encode(Data(key.xonly.bytes)) == unsigned.pubkey else {
            throw NostrCryptoError.publicKeyMismatch
        }
        let id = try unsigned.eventID()
        let signature: Data
        do {
            signature = try key.signature(for: HashDigest(Array(id))).dataRepresentation
        } catch {
            throw NostrCryptoError.signingFailed
        }
        return try NostrEvent(unsigned: unsigned, id: NostrHex.encode(id), sig: NostrHex.encode(signature))
    }

    /// Cryptographic validity only. Request filters, clock policy and author
    /// authorization must be checked independently by a future relay consumer.
    public func verify(_ event: NostrEvent) -> Bool {
        guard let id = try? NostrHex.decode(event.id, byteCount: 32, field: "id"),
              let pubkey = try? NostrHex.decode(event.pubkey, byteCount: 32, field: "pubkey"),
              let signature = try? NostrHex.decode(event.sig, byteCount: 64, field: "sig"),
              let expected = try? event.unsigned.eventID(), id == expected else { return false }
        return verifyEventID(id, publicKey: pubkey, signature: signature)
    }

    // Internal for official BIP-340 vector tests via @testable import only.
    // App-facing API never accepts deterministic auxiliary randomness.
    func signEventIDForTesting(_ eventID: Data, privateKey: Data, auxiliaryRandomness: Data) throws -> Data {
        try requireLength(eventID, count: 32, field: "event ID")
        try requireLength(auxiliaryRandomness, count: 32, field: "auxiliary randomness")
        let key = try makePrivateKey(privateKey)
        do {
            return try key.signature(for: HashDigest(Array(eventID)), auxiliaryRand: Array(auxiliaryRandomness)).dataRepresentation
        } catch {
            throw NostrCryptoError.signingFailed
        }
    }

    private func requireLength(_ bytes: Data, count: Int, field: String) throws {
        guard bytes.count == count else {
            throw NostrCryptoError.invalidLength(field: field, expected: count, actual: bytes.count)
        }
    }

    private func makePrivateKey(_ bytes: Data) throws -> P256K.Schnorr.PrivateKey {
        try requireLength(bytes, count: 32, field: "private key")
        // Reject 0 and scalars >= curve order before reaching the C provider.
        // Bytewise range checking is validation, never scalar arithmetic.
        let order: [UInt8] = [
            0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
            0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xfe,
            0xba, 0xae, 0xdc, 0xe6, 0xaf, 0x48, 0xa0, 0x3b,
            0xbf, 0xd2, 0x5e, 0x8c, 0xd0, 0x36, 0x41, 0x41
        ]
        guard bytes.contains(where: { $0 != 0 }), bytes.lexicographicallyPrecedes(order) else {
            throw NostrCryptoError.invalidPrivateKey
        }
        do {
            return try P256K.Schnorr.PrivateKey(dataRepresentation: bytes)
        } catch {
            throw NostrCryptoError.invalidPrivateKey
        }
    }
}
