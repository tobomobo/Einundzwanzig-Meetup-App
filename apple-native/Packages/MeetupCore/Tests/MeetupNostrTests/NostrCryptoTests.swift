import Foundation
import Testing
@testable import MeetupNostr

@Suite struct NostrCryptoTests {
    @Test func officialBIP340VerificationVectorsAndNostrMessagePolicy() async throws {
        let crypto = NostrCrypto()
        var fixedLengthCount = 0
        var negativeCount = 0
        var variableLengthCount = 0
        for vector in try BIP340Vector.load() {
            let result = await crypto.verifyEventID(vector.message, publicKey: vector.publicKey, signature: vector.signature)
            if vector.message.count == 32 {
                #expect(result == vector.valid, "BIP-340 vector \(vector.index)")
                fixedLengthCount += 1
                if !vector.valid { negativeCount += 1 }
            } else {
                #expect(vector.valid)
                #expect(!result, "Nostr rejects general BIP-340 variable-length vector \(vector.index)")
                await #expect(throws: NostrCryptoError.self) {
                    try await crypto.signEventID(vector.message, privateKey: vector.secretKey)
                }
                variableLengthCount += 1
            }
        }
        #expect(fixedLengthCount == 15)
        #expect(negativeCount == 10)
        #expect(variableLengthCount == 4)
    }

    @Test func officialDeterministicSignaturesIncludingVectorZeroNoDoubleHash() async throws {
        let crypto = NostrCrypto()
        var count = 0
        for vector in try BIP340Vector.load() where !vector.secretKey.isEmpty && vector.message.count == 32 {
            let publicKey = try await crypto.derivePublicKey(privateKey: vector.secretKey)
            #expect(publicKey == vector.publicKey)
            let signature = try await crypto.signEventIDForTesting(vector.message, privateKey: vector.secretKey, auxiliaryRandomness: vector.auxiliary)
            #expect(signature == vector.signature, "Exact official signature \(vector.index), with no additional SHA-256")
            #expect(await crypto.verifyEventID(vector.message, publicKey: publicKey, signature: signature))
            count += 1
        }
        #expect(count == 4)
    }

    @Test func productionRandomAuxiliarySigningAndIndependentReturnedBytes() async throws {
        let crypto = NostrCrypto()
        let vector = try #require(BIP340Vector.load().first)
        let first = try await crypto.signEventID(vector.message, privateKey: vector.secretKey)
        let second = try await crypto.signEventID(vector.message, privateKey: vector.secretKey)
        #expect(first.count == 64)
        #expect(first != second)
        #expect(first != vector.signature)
        #expect(await crypto.verifyEventID(vector.message, publicKey: vector.publicKey, signature: first))
        #expect(await crypto.verifyEventID(vector.message, publicKey: vector.publicKey, signature: second))
        var changed = first
        changed[0] ^= 1
        #expect(await crypto.verifyEventID(vector.message, publicKey: vector.publicKey, signature: changed) == false)
        #expect(await crypto.verifyEventID(vector.message, publicKey: vector.publicKey, signature: first))
    }

    @Test func allExternalLengthsAreGuarded() async throws {
        let crypto = NostrCrypto()
        let vector = try #require(BIP340Vector.load().first)
        for size in [0, 1, 16, 31, 33, 63, 64, 65, 1_024, 65_536] {
            let input = Data(repeating: 0xa5, count: size)
            #expect(await crypto.verifyEventID(input, publicKey: vector.publicKey, signature: vector.signature) == false)
            #expect(await crypto.verifyEventID(vector.message, publicKey: input, signature: vector.signature) == false)
            await #expect(throws: NostrCryptoError.invalidLength(field: "event ID", expected: 32, actual: size)) {
                try await crypto.signEventID(input, privateKey: vector.secretKey)
            }
            await #expect(throws: NostrCryptoError.invalidLength(field: "private key", expected: 32, actual: size)) {
                try await crypto.derivePublicKey(privateKey: input)
            }
            await #expect(throws: NostrCryptoError.invalidLength(field: "private key", expected: 32, actual: size)) {
                try await crypto.signEventID(vector.message, privateKey: input)
            }
            await #expect(throws: NostrCryptoError.invalidLength(field: "auxiliary randomness", expected: 32, actual: size)) {
                try await crypto.signEventIDForTesting(vector.message, privateKey: vector.secretKey, auxiliaryRandomness: input)
            }
        }
        for size in [0, 1, 31, 32, 63, 65, 128, 65_536] {
            #expect(await crypto.verifyEventID(vector.message, publicKey: vector.publicKey, signature: Data(repeating: 0, count: size)) == false)
        }
    }

    @Test func scalarBoundariesAndInvalidXOnlyKeys() async throws {
        let crypto = NostrCrypto()
        let order = try bytes("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141")
        var aboveOrder = order
        aboveOrder[31] += 1
        for invalid in [Data(repeating: 0, count: 32), order, aboveOrder, Data(repeating: 0xff, count: 32)] {
            await #expect(throws: NostrCryptoError.invalidPrivateKey) { try await crypto.derivePublicKey(privateKey: invalid) }
            await #expect(throws: NostrCryptoError.invalidPrivateKey) {
                try await crypto.signEventID(Data(repeating: 0, count: 32), privateKey: invalid)
            }
        }
        var belowOrder = order
        belowOrder[31] -= 1
        #expect(try await crypto.derivePublicKey(privateKey: belowOrder).count == 32)
        let vector = try #require(BIP340Vector.load().first)
        for invalid in [Data(repeating: 0, count: 32), Data(repeating: 0xff, count: 32)] {
            #expect(await crypto.verifyEventID(vector.message, publicKey: invalid, signature: vector.signature) == false)
        }
        var changedDigest = vector.message
        changedDigest[31] ^= 1
        #expect(await crypto.verifyEventID(changedDigest, publicKey: vector.publicKey, signature: vector.signature) == false)
    }

    @Test func actorConcurrentUseExposesOnlySendableValues() async throws {
        let crypto = NostrCrypto()
        let unsigned = try unsignedFixture()
        try await withThrowingTaskGroup(of: NostrEvent.self) { group in
            for _ in 0..<12 { group.addTask { try await crypto.sign(unsigned, privateKey: fixturePrivateKey) } }
            var count = 0
            for try await signed in group {
                #expect(await crypto.verify(signed))
                #expect(signed.id == NostrHex.encode(try unsigned.eventID()))
                count += 1
            }
            #expect(count == 12)
        }
    }
}
