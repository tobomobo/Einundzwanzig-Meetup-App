package space.einundzwanzig.meetup.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Key derivation, Schnorr and NIP-19/bech32 parity with `package:nostr` 1.5.0 + `package:bech32`. */
class KeysNip19Test {
    @Test
    fun pubkeyFromPrivkeyVectors() {
        for (c in Fixtures.nostrInfra.arr("pubkey_from_privkey")) {
            assertEquals(c.str("pub"), Keys.pubHexFromPriv(c.str("priv")))
        }
    }

    @Test
    fun officialNip19Vectors() {
        for (c in Fixtures.nostrInfra.arr("nip19")) {
            val bech = c.str("bech32")
            val hex = c.str("hex")
            if (bech.startsWith("npub")) {
                assertEquals(bech, Nip19.encodeNpub(hex))
                assertEquals(hex, Nip19.decodeNpub(bech))
            } else {
                assertEquals(bech, Nip19.encodeNsec(hex))
                assertEquals(hex, Nip19.decodeNsec(bech))
            }
        }
        for (c in Fixtures.identity.obj("nip19").arr("cases")) {
            assertEquals(c.str("npub"), Nip19.encodeNpub(c.str("hex")))
            assertEquals(c.str("hex"), Nip19.decodeNpub(c.str("npub")))
        }
        // keys of fixture 02 and 04
        val keys = Fixtures.badges.obj("keys")
        for (name in listOf("organizer", "attendee")) {
            assertEquals(keys.obj(name).str("npub"), Nip19.encodeNpub(keys.obj(name).str("pub")))
        }
        assertEquals(Fixtures.trust.obj("keys").str("npub"), KeyPair.fromPrivateKeyHex(Fixtures.trust.obj("keys").str("privkey_hex")).npub)
    }

    @Test
    fun decodeWithWrongHrpReturnsEmptyString() {
        val nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
        val npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
        assertEquals("", Nip19.decodeNpub(nsec))
        assertEquals("", Nip19.decodeNsec(npub))
        assertEquals("", Nip19.decodeNote(npub))
    }

    @Test
    fun bech32QuirksOfTheDartPackage() {
        // all-uppercase is accepted and lower-cased
        assertEquals(
            "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e",
            Nip19.decodeNpub("NPUB10ELFCS4FR0L0R8AF98JLMGDH9C8TCXJVZ9QKW038JS35MP4DMA8QZVJPTG"),
        )
        // mixed case, broken checksum, too long → exception
        assertThrows(Bech32Exception::class.java) { Nip19.decodeNpub("npub10ELFcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg") }
        assertThrows(Bech32Exception::class.java) { Nip19.decodeNpub("npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptq") }
        assertThrows(Bech32Exception::class.java) { Nip19.decodeNpub("npub1" + "q".repeat(90)) }
        assertThrows(Bech32Exception::class.java) { Nip19.decodeNpub("npubnoseparator") }
        assertThrows(Bech32Exception::class.java) { Nip19.decodeNpub("1qqqqqqqq") }
        // encode accepts uppercase hex and short payloads (Dart: npub140xserft56 for "abcd")
        assertEquals(
            "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg",
            Nip19.encodeNpub("7E7E9C42A91BFEF19FA929E5FDA1B72E0EBC1A4C1141673E2794234D86ADDF4E"),
        )
        assertEquals("npub140xserft56", Nip19.encodeNpub("abcd"))
        assertEquals("abcd", Nip19.decodeNpub("npub140xserft56"))
        assertThrows(IllegalArgumentException::class.java) { Nip19.encodeNpub("xyz") }
    }

    @Test
    fun validityHelpersAndShortening() {
        val npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
        assertTrue(Nip19.isValidNpub(npub))
        assertFalse(Nip19.isValidNpub(npub.uppercase())) // startsWith is case-sensitive
        assertFalse(Nip19.isValidNpub("npub1xyz"))
        assertTrue(Nip19.isValidNsec("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"))
        for (c in Fixtures.nostrInfra.arr("shorten")) {
            if (c.str("fn").startsWith("NostrService.shortenNpub")) {
                assertEquals(c.str("out"), Nip19.shortenNpub(c.str("in")))
            }
        }
        assertEquals("npub1short", Nip19.shortenNpub("npub1short"))
    }

    @Test
    fun noteRoundTrip() {
        val id = "4b697394206581b03ca5222b37449a9cdca1741b122d78defc177444e2536f49"
        val note = Nip19.encodeNote(id)
        assertTrue(note.startsWith("note1"))
        assertEquals(id, Nip19.decodeNote(note))
    }

    @Test
    fun tlvEntities() {
        // NIP-19 specification example
        val nprofile = "nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p"
        val profile = Nip19.decodeNprofile(nprofile)
        assertEquals("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d", profile.pubkeyHex)
        assertEquals(listOf("wss://r.x.com", "wss://djbas.sadkb.com"), profile.relays)
        assertEquals(nprofile, Nip19.encodeNprofile(profile))

        val event = Nip19.EventPointer(
            idHex = "4b697394206581b03ca5222b37449a9cdca1741b122d78defc177444e2536f49",
            relays = listOf("wss://relay.example"),
            authorHex = "981cc2078af05b62ee1f98cff325aac755bf5c5836a265c254447b5933c6223b",
            kind = 1,
        )
        assertEquals(event, Nip19.decodeNevent(Nip19.encodeNevent(event)))

        val addr = Nip19.AddressPointer("zitadelle-2026", "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 31923, listOf("wss://a.test"))
        val naddr = Nip19.encodeNaddr(addr)
        assertTrue(naddr.startsWith("naddr1"))
        assertEquals(addr, Nip19.decodeNaddr(naddr))
        assertThrows(Bech32Exception::class.java) { Nip19.decodeNevent(naddr) }
    }

    @Test
    fun keyPairLifecycle() {
        val kp = KeyPair.generate()
        assertEquals(64, kp.privateKeyHex.length)
        assertEquals(64, kp.publicKeyHex.length)
        assertEquals(kp.publicKeyHex, Keys.pubHexFromPriv(kp.privateKeyHex))
        assertEquals(kp, KeyPair.fromNsec(kp.nsec))
        assertEquals(kp.publicKeyHex, Nip19.decodeNpub(kp.npub))
        assertFalse(kp.toString().contains(kp.privateKeyHex))
        assertNotEquals(KeyPair.generate().privateKeyHex, kp.privateKeyHex)
        // NIP-01 sign/verify round trip
        val msg = Sha256.digest(DartUtf8.encode("hallo"))
        val sig = kp.sign(msg)
        assertTrue(Schnorr.verify(sig, msg, kp.publicKey))
        assertFalse(Schnorr.verify(sig, Sha256.digest(DartUtf8.encode("hallO")), kp.publicKey))
        assertArrayEquals(kp.privateKey(), kp.privateKeyHex.hexToBytes())
    }

    @Test
    fun invalidPrivateKeysAreRejected() {
        val n = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"
        for (bad in listOf("00".repeat(32), n, "ff".repeat(32), "ab", "zz".repeat(32))) {
            assertFalse(bad, Keys.isValidPrivHex(bad))
            assertThrows(bad, IllegalArgumentException::class.java) { KeyPair.fromPrivateKeyHex(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyPair.fromNsec("npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg")
        }
        assertTrue(Keys.isValidPrivHex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364140"))
    }

    @Test
    fun publicKeyValidation() {
        assertTrue(Keys.isValidPubHex("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"))
        assertFalse(Keys.isValidPubHex("00".repeat(32))) // point of order 3 on the twist
        assertFalse(Keys.isValidPubHex("ff".repeat(32)))
        assertFalse(Keys.isValidPubHex("xyz"))
    }

    @Test
    fun schnorrVerifyNeverThrows() {
        assertFalse(Schnorr.verifyHex("xyz", "00".repeat(32), "00".repeat(64)))
        assertFalse(Schnorr.verifyHex("00".repeat(32), "00".repeat(32), "00".repeat(64)))
        assertFalse(Schnorr.verifyHex("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", "00".repeat(32), ""))
        assertFalse(Schnorr.verify(ByteArray(10), ByteArray(32), ByteArray(32)))
    }
}
