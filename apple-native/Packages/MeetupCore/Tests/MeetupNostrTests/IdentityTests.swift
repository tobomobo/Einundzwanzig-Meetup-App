import Foundation
import Testing
@testable import MeetupNostr

@Suite struct IdentityTests {
    @Test func flutterLegacyJSONRequiresAllThreeMatchingRepresentations() throws {
        let secret = try IdentityKey.parse(String(repeating: "01", count: 32))
        let fields: [String: String] = ["flutter.nostr_nsec_key": try NIP19.encodeSecretKey(secret),
            "flutter.nostr_npub_key": try NIP19.encodePublicKey(IdentityKey.publicKey(secret)),
            "flutter.nostr_priv_hex": IdentityKey.hex(secret)]
        #expect(try FlutterIdentityMigration.privateKey(json: JSONEncoder().encode(fields)) == secret)
        var changed = fields; changed["flutter.nostr_priv_hex"] = String(repeating: "02", count: 32)
        #expect(throws: IdentityError.inconsistentBackup) { try FlutterIdentityMigration.privateKey(json: JSONEncoder().encode(changed)) }
        changed.removeValue(forKey: "flutter.nostr_priv_hex")
        #expect(throws: IdentityError.invalidBackup) { try FlutterIdentityMigration.privateKey(json: JSONEncoder().encode(changed)) }
    }
    @Test func secretImportsAreCanonicalAndRevocationInvalidatesRetainedSigner() async throws {
        let secret = try IdentityKey.parse(String(repeating: "01", count: 32))
        let nsec = try NIP19.encodeSecretKey(secret)
        #expect(try IdentityKey.parse(nsec) == secret)
        #expect(try IdentityKey.parse(nsec.uppercased()) == secret)
        #expect(throws: IdentityError.invalidEncoding) { try IdentityKey.parse("N" + nsec.dropFirst()) }
        let npub = try NIP19.encodePublicKey(IdentityKey.publicKey(secret))
        #expect(try NIP19.decodePublicKey(npub) == IdentityKey.publicKey(secret))
        #expect(throws: IdentityError.invalidSecret) { try IdentityKey.parse(String(repeating: "0", count: 64)) }
        let signer = try LocalIdentitySigner(privateKey: secret)
        let key = try await signer.publicKey(), encrypted = try await signer.encrypt("wallet fixture", to: key)
        #expect(try await signer.decrypt(encrypted, from: key) == "wallet fixture")
        await signer.invalidate()
        await #expect(throws: IdentityError.locked) { try await signer.publicKey() }
        await #expect(throws: IdentityError.locked) { try await signer.sign(kind: 1, tags: [], content: "blocked", createdAt: 1) }
        await #expect(throws: IdentityError.locked) { try await signer.decrypt(encrypted, from: key) }
    }
}
