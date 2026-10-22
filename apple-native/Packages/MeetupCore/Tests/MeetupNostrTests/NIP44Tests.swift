import Foundation
import Testing
import P256K
@testable import MeetupNostr

@Suite struct NIP44Tests {
    private func vectors(_ section: String) throws -> [String: Any] {
        let url = try #require(Bundle.module.url(forResource: "NIP44-vectors", withExtension: "json", subdirectory: "Fixtures"))
        let root = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        return try #require((root["v2"] as? [String: Any])?[section] as? [String: Any])
    }
    private func hex(_ text: String) throws -> Data { try NostrHex.decode(text, byteCount: text.utf8.count / 2, field: "fixture") }
    @Test func allOfficialConversationAndMessageKeys() throws {
        let valid = try vectors("valid")
        let conversations = try #require(valid["get_conversation_key"] as? [[String: String]])
        #expect(conversations.count == 35)
        for v in conversations {
            let actual = try NIP44.conversationKey(privateKey: hex(#require(v["sec1"])), publicKey: #require(v["pub2"]))
            #expect(IdentityKey.hex(actual) == v["conversation_key"])
        }
        let keys = try #require(valid["get_message_keys"] as? [String: Any])
        let ck = try hex(#require(keys["conversation_key"] as? String))
        let cases = try #require(keys["keys"] as? [[String: String]])
        #expect(cases.count == 32)
        for v in cases {
            let k = try NIP44.messageKeys(ck, nonce: hex(#require(v["nonce"])))
            #expect(IdentityKey.hex(Data(k.chacha)) == v["chacha_key"])
            #expect(IdentityKey.hex(Data(k.nonce)) == v["chacha_nonce"])
            #expect(IdentityKey.hex(Data(k.hmac)) == v["hmac_key"])
        }
        let padding = try #require(valid["calc_padded_len"] as? [[Int]])
        #expect(padding.count == 24)
        for pair in padding { #expect(NIP44.paddedLength(pair[0]) == pair[1]) }
    }
    @Test func officialEncryptionBothDirectionsAndLargeMessages() throws {
        let valid = try vectors("valid")
        let cases = try #require(valid["encrypt_decrypt"] as? [[String: String]])
        #expect(cases.count == 10)
        for v in cases {
            let a = try hex(#require(v["sec1"])), b = try hex(#require(v["sec2"]))
            let ck = try NIP44.conversationKey(privateKey: a, publicKey: IdentityKey.publicKey(b))
            #expect(IdentityKey.hex(ck) == v["conversation_key"])
            #expect(try NIP44.conversationKey(privateKey: b, publicKey: IdentityKey.publicKey(a)) == ck)
            let plain = try #require(v["plaintext"]), payload = try #require(v["payload"])
            #expect(try NIP44.encrypt(plain, conversationKey: ck, nonce: hex(#require(v["nonce"]))) == payload)
            #expect(try NIP44.decrypt(payload, conversationKey: ck) == plain)
        }
        let longCases = try #require(valid["encrypt_decrypt_long_msg"] as? [[String: Any]])
        #expect(longCases.count == 3)
        for v in longCases {
            let ck = try hex(#require(v["conversation_key"] as? String)), nonce = try hex(#require(v["nonce"] as? String))
            let plain = String(repeating: try #require(v["pattern"] as? String), count: try #require(v["repeat"] as? Int))
            let payload = try NIP44.encrypt(plain, conversationKey: ck, nonce: nonce)
            #expect(IdentityKey.hex(Data(SHA256.hash(data: Data(plain.utf8)))) == v["plaintext_sha256"] as? String)
            #expect(IdentityKey.hex(Data(SHA256.hash(data: Data(payload.utf8)))) == v["payload_sha256"] as? String)
            #expect(try NIP44.decrypt(payload, conversationKey: ck) == plain)
        }
    }
    @Test func officialNegativeVectorsRejectTwistsMACPaddingVersionAndBounds() throws {
        let invalid = try vectors("invalid")
        let conversations = try #require(invalid["get_conversation_key"] as? [[String: String]])
        #expect(conversations.count == 8)
        for v in conversations {
            #expect(throws: (any Error).self) { try NIP44.conversationKey(privateKey: hex(#require(v["sec1"])), publicKey: #require(v["pub2"])) }
        }
        let cases = try #require(invalid["decrypt"] as? [[String: String]])
        #expect(cases.count == 12)
        for v in cases {
            #expect(throws: (any Error).self) { try NIP44.decrypt(#require(v["payload"]), conversationKey: hex(#require(v["conversation_key"]))) }
        }
        for length in try #require(invalid["encrypt_msg_lengths"] as? [Int]) {
            #expect(throws: IdentityError.resourceLimit) { try NIP44.encrypt(String(repeating: "a", count: length), conversationKey: Data(repeating: 0, count: 32)) }
        }
        let peer = try IdentityKey.publicKey(IdentityKey.parse(String(repeating: "01", count: 32)))
        for scalar in [String(repeating: "0", count: 64), String(repeating: "f", count: 64), "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"] {
            #expect(throws: IdentityError.invalidSecret) { try NIP44.conversationKey(privateKey: hex(scalar), publicKey: peer) }
        }
    }
}
