package space.einundzwanzig.meetup.core.crypto

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Port of test/nip44_vectors_test.dart (official paulmillr/nip44 vectors incl. negative cases),
 * test/nip44_web_smoke_test.dart (literal subset) and the crypto part of test/signer_nip44_test.dart.
 */
class Nip44VectorsTest {
    private val v2 = Fixtures.nip44.obj("v2")
    private val valid = v2.obj("valid")
    private val invalid = v2.obj("invalid")

    @Test
    fun fixtureIsTheOfficialFile() {
        val bytes = Fixtures::class.java.classLoader!!.getResourceAsStream("nip44.vectors.json")!!.readBytes()
        assertEquals("269ed0f69e4c192512cc779e78c555090cebc7c785b609e338a62afc3ce25040", Sha256.hex(bytes))
    }

    @Test
    fun getConversationKey() {
        val list = valid.arr("get_conversation_key")
        assertEquals(35, list.size)
        for (c in list) {
            assertEquals(
                "sec1=${c.str("sec1")} pub2=${c.str("pub2")}",
                c.str("conversation_key"),
                Nip44.conversationKey(c.str("sec1"), c.str("pub2")).toHex(),
            )
        }
    }

    @Test
    fun getMessageKeys() {
        val block = valid.obj("get_message_keys")
        val ck = block.str("conversation_key").hexToBytes()
        val list = block.arr("keys")
        assertEquals(32, list.size)
        for (c in list) {
            val keys = Nip44.messageKeys(ck, c.str("nonce").hexToBytes())
            assertEquals(c.str("chacha_key"), keys.chachaKey.toHex())
            assertEquals(c.str("chacha_nonce"), keys.chachaNonce.toHex())
            assertEquals(c.str("hmac_key"), keys.hmacKey.toHex())
        }
    }

    @Test
    fun calcPaddedLen() {
        val list = valid.arr("calc_padded_len")
        assertEquals(24, list.size)
        for (pair in list) {
            val (unpadded, padded) = pair.jsonArray.map { it.jsonPrimitive.int }
            assertEquals("unpadded=$unpadded", padded, Nip44.calcPaddedLen(unpadded))
        }
    }

    @Test
    fun encryptDecryptIncludingReverseConversationKey() {
        val list = valid.arr("encrypt_decrypt")
        assertEquals(10, list.size)
        for (c in list) {
            val sec1 = c.str("sec1")
            val sec2 = c.str("sec2")
            val plaintext = c.str("plaintext")
            // Public keys derived through our own BIP-340 key derivation.
            val ck = Nip44.conversationKey(sec1, Keys.pubHexFromPriv(sec2))
            val ckReverse = Nip44.conversationKey(sec2, Keys.pubHexFromPriv(sec1))
            assertEquals(c.str("conversation_key"), ck.toHex())
            assertEquals("reverse sec2=$sec2", c.str("conversation_key"), ckReverse.toHex())
            assertEquals(c.str("payload"), Nip44.encrypt(plaintext, ck, c.str("nonce").hexToBytes()))
            assertEquals(plaintext, Nip44.decrypt(c.str("payload"), ck))
        }
    }

    @Test
    fun encryptDecryptLongMessages() {
        val list = valid.arr("encrypt_decrypt_long_msg")
        assertEquals(3, list.size)
        for (c in list) {
            val ck = c.str("conversation_key").hexToBytes()
            val plaintext = c.str("pattern").repeat(c.int("repeat"))
            assertEquals("vector itself", c.str("plaintext_sha256"), Sha256.hexOfUtf8(plaintext))
            val payload = Nip44.encrypt(plaintext, ck, c.str("nonce").hexToBytes())
            assertEquals(c.str("payload_sha256"), Sha256.hexOfUtf8(payload))
            assertEquals(plaintext, Nip44.decrypt(payload, ck))
        }
    }

    @Test
    fun invalidConversationKeys() {
        val list = invalid.arr("get_conversation_key")
        assertEquals(8, list.size)
        for (c in list) {
            assertThrows(c.str("note"), Nip44Exception::class.java) {
                Nip44.conversationKey(c.str("sec1"), c.str("pub2"))
            }
        }
    }

    @Test
    fun invalidMessageLengths() {
        val key = ByteArray(32)
        val list = invalid.arr("encrypt_msg_lengths").map { it.jsonPrimitive.int }
        assertEquals(listOf(0, 65536, 100000, 10000000), list)
        for (len in list) {
            assertThrows("length $len", Nip44Exception::class.java) { Nip44.encrypt("a".repeat(len), key) }
        }
    }

    @Test
    fun invalidPayloads() {
        val list = invalid.arr("decrypt")
        assertEquals(12, list.size)
        for (c in list) {
            assertThrows(c.str("note"), Nip44Exception::class.java) {
                Nip44.decrypt(c.str("payload"), c.str("conversation_key").hexToBytes())
            }
        }
    }

    @Test
    fun twoEncryptionsDiffer() {
        val key = ByteArray(32)
        assertNotEquals(Nip44.encrypt("hallo", key), Nip44.encrypt("hallo", key))
    }

    @Test
    fun foreignConversationKeyDoesNotDecrypt() {
        val a = ByteArray(32)
        val b = ByteArray(32).also { it[31] = 1 }
        val payload = Nip44.encrypt("geheim", a)
        assertEquals("geheim", Nip44.decrypt(payload, a))
        assertThrows(Nip44Exception::class.java) { Nip44.decrypt(payload, b) }
    }

    @Test
    fun privateKeyOutsideCurveOrderIsRejected() {
        val validPub = Keys.pubHexFromPriv("01".repeat(32))
        assertEquals(32, Nip44.conversationKey("02".repeat(32), validPub).size)
        val curveOrder = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"
        for (sec in listOf("00".repeat(32), curveOrder, "ff".repeat(32))) {
            assertThrows("sec1=$sec", Nip44Exception::class.java) { Nip44.conversationKey(sec, validPub) }
        }
    }

    @Test
    fun shortHexIsRejected() {
        assertThrows(Nip44Exception::class.java) { Nip44.conversationKey("ab", "cd") }
        assertThrows(Nip44Exception::class.java) { Nip44.conversationKey("zz".repeat(32), "cd") }
    }

    // ---- test/nip44_web_smoke_test.dart (literal values) ----

    @Test
    fun webSmokeLiterals() {
        assertEquals(
            "3dfef0ce2a4d80a25e7a328accf73448ef67096f65f79588e358d9a0eb9013f1",
            Nip44.conversationKey(
                "315e59ff51cb9209768cf7da80791ddcaae56ac9775eb25b6dee1234bc5d2268",
                "c2f9d9948dc8c7c38321e4b85c8558872eafa0641cd269db76848a6073e69133",
            ).toHex(),
        )
        val ck = Nip44.conversationKey(
            "0000000000000000000000000000000000000000000000000000000000000001",
            "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5",
        )
        assertEquals("c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d", ck.toHex())
        val payload = Nip44.encrypt("a", ck, ("00".repeat(31) + "01").hexToBytes())
        assertEquals(
            "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb",
            payload,
        )
        assertEquals("a", Nip44.decrypt(payload, ck))

        assertThrows(Nip44Exception::class.java) {
            Nip44.decrypt(
                "Agn/l3ULCEAS4V7LhGFM6IGA17jsDUaFCKhrbXDANholyySBfeh+EN8wNB9gaLlg4j6wdBYh+3oK+mnxWu3NKRbSvQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "cff7bd6a3e29a450fd27f6c125d5edeb0987c475fd1e8d97591e0d4d8a89763c".hexToBytes(),
            )
        }
        assertThrows(Nip44Exception::class.java) {
            Nip44.decrypt(
                "Anq2XbuLvCuONcr7V0UxTh8FAyWoZNEdBHXvdbNmDZHB573MI7R7rrTYftpqmvUpahmBC2sngmI14/L0HjOZ7lWGJlzdh6luiOnGPc46cGxf08MRC4CIuxx3i2Lm0KqgJ7vA",
                "5254827d29177622d40a7b67cad014fe7137700c3c523903ebbe3e1b74d40214".hexToBytes(),
            )
        }
        val random = Nip44.encrypt("Grüße vom Meetup 🧡", ck)
        assertEquals("Grüße vom Meetup 🧡", Nip44.decrypt(random, ck))
    }

    // ---- crypto part of test/signer_nip44_test.dart ----

    @Test
    fun localSignerRoundTripAndInterop() {
        val me = KeyPair.generate()
        val other = KeyPair.generate()
        val mine = Nip44.conversationKey(me.privateKeyHex, other.publicKeyHex)
        val sealed = Nip44.encrypt("hallo sync", mine)
        assertEquals("hallo sync", Nip44.decrypt(sealed, mine))
        // The other side derives the same key.
        val theirs = Nip44.conversationKey(other.privateKeyHex, me.publicKeyHex)
        assertArrayEquals(mine, theirs)
        assertEquals("rundreise", Nip44.decrypt(Nip44.encrypt("rundreise", mine), theirs))
        // canNip44 probe plaintext, encrypted to self.
        val self = Nip44.conversationKey(me.privateKeyHex, me.publicKeyHex)
        assertEquals("e21-nip44-probe", Nip44.decrypt(Nip44.encrypt("e21-nip44-probe", self), self))
    }

    @Test
    fun uppercaseHexKeysAreAccepted() {
        val lower = Nip44.conversationKey(
            "315e59ff51cb9209768cf7da80791ddcaae56ac9775eb25b6dee1234bc5d2268",
            "c2f9d9948dc8c7c38321e4b85c8558872eafa0641cd269db76848a6073e69133",
        )
        val upper = Nip44.conversationKey(
            "315E59FF51CB9209768CF7DA80791DDCAAE56AC9775EB25B6DEE1234BC5D2268",
            "C2F9D9948DC8C7C38321E4B85C8558872EAFA0641CD269DB76848A6073E69133",
        )
        assertArrayEquals(lower, upper)
    }

    @Test
    fun messageKeyArgumentChecks() {
        assertThrows(Nip44Exception::class.java) { Nip44.messageKeys(ByteArray(31), ByteArray(32)) }
        assertThrows(Nip44Exception::class.java) { Nip44.messageKeys(ByteArray(32), ByteArray(31)) }
    }
}
