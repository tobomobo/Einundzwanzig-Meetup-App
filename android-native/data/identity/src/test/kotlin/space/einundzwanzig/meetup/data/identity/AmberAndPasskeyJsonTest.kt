package space.einundzwanzig.meetup.data.identity

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.data.identity.nip55.Nip55Exception
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyJson
import space.einundzwanzig.meetup.data.identity.signer.AmberNostrSigner

/** Spec 01 §10.17: Amber `get_public_key` parsing and the passkey JSON builders. */
class AmberAndPasskeyJsonTest {

    private val hex = "fa5e3477d2d6b92d667dcab66c8bbb1527014599c5eecddd545e3a39d7870268"
    private val npub = "npub1lf0rga7j66uj6enae2mxezamz5nsz3vechhvmh25tcarn4u8qf5q534jzc"

    @Test
    fun amberPublicKeyFormats() {
        val ok = AmberConnectResult.Success(hex, npub)
        assertEquals(ok, AmberNostrSigner.parsePublicKey(npub))
        assertEquals(ok, AmberNostrSigner.parsePublicKey("  ${hex.uppercase()} "))
        assertEquals(ok, AmberNostrSigner.parsePublicKey("""{"result":"$npub"}"""))
        assertEquals(ok, AmberNostrSigner.parsePublicKey("""{"npub":"$npub"}"""))
        assertEquals(ok, AmberNostrSigner.parsePublicKey("""{"pubkey":"$hex"}"""))
        assertEquals(AmberConnectResult.Error("Unerwartetes Schlüsselformat von Amber."), AmberNostrSigner.parsePublicKey("hallo"))
        assertTrue(AmberNostrSigner.parsePublicKey("npub1zzzz") is AmberConnectResult.Error)
    }

    @Test
    fun amberErrorCodesMapToExceptions() {
        assertTrue(AmberNostrSigner.mapError(Nip55Exception("cancelled")) is SigningCancelledException)
        assertTrue(AmberNostrSigner.mapError(Nip55Exception("rejected")) is SigningCancelledException)
        assertTrue(AmberNostrSigner.mapError(Nip55Exception("signer_missing")) is SignerMissingException)
        assertEquals("Amber-Fehler: Eine Signer-Anfrage läuft bereits", AmberNostrSigner.mapError(Nip55Exception("busy", "Eine Signer-Anfrage läuft bereits")).message)
        assertEquals("Amber-Fehler: launch_failed", AmberNostrSigner.mapError(Nip55Exception("launch_failed")).message)
    }

    private val passkey = Fixtures.identity["passkeyPrf"]!!.jsonObject

    @Test
    fun passkeyConstantsMatchTheFixture() {
        assertEquals(passkey["rpId"]!!.jsonPrimitive.content, PasskeyJson.RP_ID)
        assertEquals(passkey["rpName"]!!.jsonPrimitive.content, PasskeyJson.RP_NAME)
        assertEquals(passkey["saltUtf8"]!!.jsonPrimitive.content, PasskeyJson.PRF_SALT)
        assertEquals(passkey["saltBase64UrlNoPad"]!!.jsonPrimitive.content, PasskeyJson.prfSaltB64)
    }

    @Test
    fun registrationJsonIsByteCompatible() {
        val pub = KeyPair.fromPrivateKeyHex("01".repeat(32)).publicKeyHex
        val json = PasskeyJson.registrationRequest(pub, "Ralph", "CHALLENGE")
        val userId = java.util.Base64.getUrlEncoder().encodeToString(java.math.BigInteger(pub, 16).toByteArray().takeLast(32).toByteArray())
        assertEquals(
            """{"rp":{"id":"einundzwanzig.space","name":"21meetup"},"user":{"name":"Ralph","displayName":"Ralph","id":"$userId"},""" +
                """"challenge":"CHALLENGE","attestation":"none","timeout":60000,"pubKeyCredParams":[{"type":"public-key","alg":-7},""" +
                """{"type":"public-key","alg":-257}],"authenticatorSelection":{"authenticatorAttachment":"platform",""" +
                """"requireResidentKey":false,"residentKey":"preferred","userVerification":"preferred"},"excludeCredentials":[],""" +
                """"extensions":{"prf":{"eval":{"first":"MjFtZWV0dXAtZWFzeWF1dGgtcHJmLXYx"}}}}""",
            json,
        )
        assertTrue("user.id keeps its '=' padding", userId.endsWith("="))
    }

    @Test
    fun authenticationJsonUsesTheConstantRpId() {
        assertEquals(
            """{"challenge":"C","timeout":60000,"rpId":"einundzwanzig.space","allowCredentials":[{"type":"public-key","id":"cred","transports":[]}],""" +
                """"userVerification":"preferred","extensions":{"prf":{"eval":{"first":"MjFtZWV0dXAtZWFzeWF1dGgtcHJmLXYx"}}}}""",
            PasskeyJson.authenticationRequest(listOf("cred"), "C"),
        )
        val noAllow = DartJson.decode(PasskeyJson.authenticationRequest(emptyList(), "C")).jsonObject
        assertNull(noAllow["allowCredentials"])
        assertEquals(43, PasskeyJson.randomChallenge().length)
    }

    @Test
    fun prfOutputIsLowercaseHex() {
        val secret = ByteArray(32) { (it * 7).toByte() }
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        val response = """{"id":"cred-1","clientExtensionResults":{"prf":{"results":{"first":"$b64"}}}}"""
        assertEquals(secret.joinToString("") { "%02x".format(it) }, PasskeyJson.prfSecretHex(response))
        assertEquals("cred-1", PasskeyJson.credentialId(response))
        assertNull(PasskeyJson.prfSecretHex("""{"id":"x","clientExtensionResults":{}}"""))
        assertNull(PasskeyJson.prfSecretHex("""{"clientExtensionResults":{"prf":{"results":{"first":""}}}}"""))
    }

    @Test
    fun malformedResponseHasNoCredentialId() {
        assertNull(PasskeyJson.credentialId("not json"))
        assertNull(PasskeyJson.credentialId("""{"id":42}"""))
        assertNull(PasskeyJson.credentialId("[]"))
        assertNull(PasskeyJson.credentialId(""))
    }
}
