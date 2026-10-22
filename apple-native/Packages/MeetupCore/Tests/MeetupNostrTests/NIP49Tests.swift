import Foundation
import Testing
@testable import MeetupNostr

@Suite(.serialized) struct NIP49Tests {
    // Official NIP-49 decryption vector, mirrored by Flutter's nip49_test.dart.
    let official = "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"
    @Test func officialDecryptionVectorAndWrongPassword() throws {
        #expect(try IdentityKey.hex(NIP49.decrypt(official, password: "nostr")) == "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683")
        #expect(throws: IdentityError.authenticationFailed) { try NIP49.decrypt(official, password: "wrong") }
    }
    @Test func officialUnicodeNFKCInteroperability() throws {
        let encoded = "ncryptsec1qgy5kwr5v8p206vwaflp4g6r083kwts6q5sh8m4d0q56edpxwhrly78ema2z7jpdeldsz7u5wpxpyhs6m0405skdsep9n37uncw7xlc8q8meyw6d6ky47vcl0guhqpt5dx8ejxc8hvzf6y2gwsl5s0nw"
        for password in ["\u{212b}\u{2126}\u{1e9b}\u{0323}", "\u{00c5}\u{03a9}\u{1e69}"] {
            #expect(try IdentityKey.hex(NIP49.decrypt(encoded, password: password)) == "11b25a101667dd9208db93c0827c6bdad66729a5b521156a7e9d3b22b3ae8944")
        }
    }
    @Test func securityByteIsAuthenticatedAndHostileCostRejectedBeforeKDF() throws {
        let secret = try IdentityKey.parse(String(repeating: "11", count: 32))
        let encoded = try NIP49.encrypt(secret, password: "fixture", logN: 8, security: .secure)
        var payload = try NIP19.decode(encoded, prefix: "ncryptsec", count: 91)
        payload[42] = 0
        #expect(throws: IdentityError.authenticationFailed) { try NIP49.decrypt(NIP19.encode(prefix: "ncryptsec", bytes: payload), password: "fixture") }
        payload[1] = 255
        #expect(throws: IdentityError.resourceLimit) { try NIP49.decrypt(NIP19.encode(prefix: "ncryptsec", bytes: payload), password: "fixture") }
        let replacement = encoded.last == "q" ? "p" : "q"
        #expect(throws: IdentityError.invalidEncoding) { try NIP49.decrypt(String(encoded.dropLast()) + replacement, password: "fixture") }
    }
}
