import Foundation
import Testing
@testable import MeetupNostr

@Suite(.serialized) struct BackupInteropTests {
    private func fixture(_ name: String, _ ext: String) throws -> Data {
        let url = try #require(Bundle.module.url(forResource: name, withExtension: ext, subdirectory: "Fixtures/BackupInterop"))
        return try Data(contentsOf: url)
    }
    private func password() throws -> String {
        let provenance = try JSONDecoder().decode([String: String].self, from: fixture("provenance", "json"))
        return try #require(provenance["password"])
    }
    @Test func actualUnmodifiedFlutterExporterDecryptsAndPreservesEverySection() throws {
        let payload = String(decoding: try fixture("flutter-v5", "21bkp"), as: UTF8.self)
        let data = try BackupEncryption.decrypt(payload, password: password())
        let expected = try BackupDocument.decode(fixture("flutter-v5", "json")), actual = try BackupDocument.decode(data)
        #expect(actual == expected)
        #expect(try IdentityKey.hex(actual.localPrivateKey()!) == String(repeating: "0", count: 63) + "1")
        #expect(actual.fields["user"]?.object?["fullName"]?.string == "Änne 😀")
        #expect(actual.fields["user"]?.object?["favoriteMeetupIds"]?.array?.count == 2)
        #expect(actual.fields["badges"]?.array?.count == 2)
        #expect(actual.fields["badges"]?.array?[0].object?["presenceVerified"]?.bool == true)
        #expect(actual.fields["badges"]?.array?[1].object?["isEvent"]?.bool == true)
    }
    @Test func flutterBackupTamperAndInconsistentIdentityAreTypedFailures() throws {
        let password = try password(), payload = String(decoding: try fixture("flutter-v5", "21bkp"), as: UTF8.self)
        var parts = payload.split(separator: ":").map(String.init)
        var sealed = try #require(Data(base64Encoded: parts[3]))
        sealed[0] ^= 1; parts[3] = sealed.base64EncodedString()
        #expect(throws: IdentityError.authenticationFailed) { try BackupEncryption.decrypt(parts.joined(separator: ":"), password: password) }
        var document = try BackupDocument.decode(fixture("flutter-v5", "json"))
        var block = try #require(document.fields["nostr"]?.object)
        block["priv_hex"] = .string(String(repeating: "02", count: 32)); document.fields["nostr"] = .object(block)
        #expect(throws: IdentityError.inconsistentBackup) { try document.localPrivateKey() }
    }
    @Test func swiftExporterProducesPortableEncV2AndImportedAuthorityRemainsPending() throws {
        let original = try BackupDocument.decode(fixture("flutter-v5", "json"))
        let data = try original.encoded(), password = try password()
        let output = try BackupEncryption.encrypt(data, password: password, salt: Data(0..<32), iv: Data(32..<48))
        #expect(output.hasPrefix("enc_v2:"))
        #expect(try BackupDocument.decode(BackupEncryption.decrypt(output, password: password)) == original)
        var claims = original
        claims.fields["humanity_proof"] = .object(["verified": .bool(true), "receipt_event_id": .string("untrusted")])
        claims.fields["admin_registry"] = .array([.object(["npub": .string("untrusted")])])
        let pending = claims.pendingRevalidation()
        #expect(pending.fields["humanity_proof"]?.object?["verified"]?.bool == false)
        #expect(pending.fields["humanity_proof"]?.object?["needs_reverification"]?.bool == true)
        #expect(pending.fields["admin_registry"] == nil)
        #expect(pending.fields["user"]?.object?["isNostrVerified"]?.bool == false)
        // A public synthetic output for the actual Flutter importer round trip.
        if let path = ProcessInfo.processInfo.environment["SWIFT_BACKUP_INTEROP_OUTPUT"] {
            try output.write(toFile: path, atomically: true, encoding: .utf8)
        }
    }
}
