package space.einundzwanzig.meetup.data.identity

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.AbortError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyErrors
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfException
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfile as Profile
import space.einundzwanzig.meetup.data.identity.review.AppReviewDemo
import space.einundzwanzig.meetup.data.identity.signer.SignerActor
import space.einundzwanzig.meetup.data.identity.signer.verifySignerResponse
import java.util.concurrent.CopyOnWriteArrayList

/** Profile flow, passkey error mapping, Dart trim semantics and typed signing errors. */
class ReviewFollowupTest {

    // ---------------- profile flow ----------------

    private suspend fun eventually(check: () -> Boolean) {
        repeat(200) {
            if (check()) return
            delay(10)
        }
        assertTrue("condition not reached", check())
    }

    @Test
    fun profileFlowSeesKeyChangesWithoutAnyPrefChange() = runBlocking {
        val id = TestIdentity()
        val first = id.keys.generateKeyPair()
        id.signing.useLocalMode()
        val seen = CopyOnWriteArrayList<UserProfile>()
        val job = CoroutineScope(Dispatchers.Default).launch { id.profiles.profile.collect { seen += it } }
        eventually { seen.lastOrNull()?.nostrNpub == first.npub }

        // Importing another key in local mode writes the same signing_mode: only the key store changes.
        val second = id.keys.importPrivHex(KeyPair.generate().privateKeyHex)
        eventually { seen.last().nostrNpub == second.npub }

        id.keyStore.deleteKeys()
        eventually { !seen.last().hasNostrKey }
        job.cancel()
    }

    @Test
    fun unreadableSecureStoreDoesNotEndTheProfileFlow() = runBlocking {
        val id = TestIdentity()
        val key = id.keys.generateKeyPair()
        val seen = CopyOnWriteArrayList<UserProfile>()
        val job = CoroutineScope(Dispatchers.Default).launch { id.profiles.profile.collect { seen += it } }
        eventually { seen.isNotEmpty() }

        id.secure.failRead = { it == IdentityKeys.NSEC }
        id.prefs.setString("nickname", "Satoshi")
        delay(100)
        assertTrue(job.isActive)

        id.secure.failRead = { false }
        id.prefs.setString("nickname", "Hal")
        eventually { seen.last().nickname == "Hal" }
        assertEquals(key.npub, seen.last().nostrNpub)
        job.cancel()
    }

    @Test
    fun unrelatedPrefWritesDoNotReloadTheProfile() = runBlocking {
        val id = TestIdentity()
        id.keys.generateKeyPair()
        val seen = CopyOnWriteArrayList<UserProfile>()
        val job = CoroutineScope(Dispatchers.Default).launch { id.profiles.profile.collect { seen += it } }
        eventually { seen.isNotEmpty() }
        var reads = 0
        id.secure.failRead = { reads++; false }
        id.prefs.setString("mempool_url", "https://mempool.space")
        id.prefs.setString("app_locale", "de")
        delay(100)
        assertEquals(0, reads)
        job.cancel()
    }

    // ---------------- passkey error mapping ----------------

    @Test
    fun missingAssetLinksAndProviderProblemsReadAsUnavailable() {
        val rpUnknown = PasskeyErrors.create(CreateCredentialUnknownException("RP ID cannot be validated."))
        assertEquals(PasskeyPrfException.DOMAIN_NOT_ASSOCIATED, rpUnknown.code)
        assertTrue(rpUnknown.isUnavailable)
        assertTrue(PasskeyErrors.get(GetCredentialUnknownException("The incoming request cannot be validated")).isUnavailable)
        assertEquals(
            PasskeyPrfException.UNSUPPORTED,
            PasskeyErrors.create(CreateCredentialProviderConfigurationException("no provider")).code,
        )
        assertTrue(PasskeyErrors.get(GetCredentialProviderConfigurationException("old gms")).isUnavailable)
        assertEquals(
            PasskeyPrfException.DOMAIN_NOT_ASSOCIATED,
            PasskeyErrors.create(CreatePublicKeyCredentialDomException(SecurityError(), "rp mismatch")).code,
        )
        // A NotAllowedError carrying an RP validation failure is "unavailable", not a silent cancel.
        val rpNotAllowed = PasskeyErrors.create(CreatePublicKeyCredentialDomException(NotAllowedError(), "RP ID cannot be validated"))
        assertEquals(PasskeyPrfException.DOMAIN_NOT_ASSOCIATED, rpNotAllowed.code)
        assertFalse(rpNotAllowed.isCancelled)
        assertTrue(
            PasskeyErrors.get(GetPublicKeyCredentialDomException(NotAllowedError(), "The incoming request cannot be validated"))
                .isUnavailable,
        )
        // A plain NotAllowedError stays a cancellation.
        assertTrue(
            PasskeyErrors.create(
                CreatePublicKeyCredentialDomException(NotAllowedError(), "The operation either timed out or was not allowed."),
            ).isCancelled,
        )
        // Anything else the Flutter plugin reported as android-unhandled is "unavailable" there too.
        val other = PasskeyErrors.create(CreateCredentialUnknownException("weird"))
        assertEquals(PasskeyPrfException.UNHANDLED, other.code)
        assertTrue(other.isUnavailable)
        assertTrue(PasskeyErrors.create(CreateCredentialInterruptedException("x")).isUnavailable)
    }

    @Test
    fun cancellationsAndNamedPlatformErrorsKeepTheirMeaning() {
        assertTrue(PasskeyErrors.create(CreateCredentialCancellationException()).isCancelled)
        assertTrue(PasskeyErrors.create(CreateCredentialUnknownException("Unable to create key during registration")).isCancelled)
        assertEquals(PasskeyPrfException.NO_CREDENTIAL, PasskeyErrors.get(NoCredentialException()).code)
        val timeout = PasskeyErrors.get(GetPublicKeyCredentialDomException(AbortError(), "[15] Flow has timed out."))
        assertEquals(PasskeyPrfException.FAILED, timeout.code)
        assertFalse(timeout.isUnavailable)
        assertEquals("[15] Flow has timed out.", timeout.message)
    }

    // ---------------- Dart trim ----------------

    @Test
    fun dartTrimSemanticsForNicknameAndReviewLogin() {
        // Dart trim() removes U+FEFF and U+0085, Kotlin trim() does not.
        assertTrue(Profile(nickname = "﻿Anon").hasCustomNickname.not())
        assertTrue(Profile(nickname = "\u0085Anon\u0085").hasCustomNickname.not())
        assertTrue(AppReviewDemo.isDemoLogin("\u0085AppReview", AppReviewDemo.PASSWORD))
        assertTrue(AppReviewDemo.matches("﻿" + AppReviewDemo.PAYLOAD))
    }

    @Test
    fun importTrimsLikeDart() = runBlocking {
        val id = TestIdentity()
        val pair = KeyPair.generate()
        assertEquals(pair.npub, id.keys.importNsec("﻿" + Nip19.encodeNsec(pair.privateKeyHex) + "\u0085").npub)
        assertEquals(pair.npub, id.keys.importPrivHex("\u0085" + pair.privateKeyHex.uppercase()).npub)
    }

    @Test
    fun registerRejectsANicknameThatIsOnlyDartWhitespace() = runBlocking {
        val id = TestIdentity()
        val error = runCatching { id.easyAuth.register("﻿\u0085", "passwort123") }.exceptionOrNull()
        assertTrue("got $error", error is LocalEasyAuthException)
    }

    // ---------------- typed signing errors ----------------

    @Test
    fun verifierFailuresCarryAMachineReadableReason() {
        val kc = KeyPair.generate()
        fun verify(signed: kotlinx.serialization.json.JsonObject) =
            runCatching {
                verifySignerResponse(signed, kc.publicKeyHex, 1, emptyList(), "hallo", 1_786_000_000, SignerActor.REMOTE_SIGNER)
            }.exceptionOrNull() as SigningException

        assertEquals(
            SigningError.SignerNoEvent(SignerActor.REMOTE_SIGNER),
            verify(jsonObjectOf("pubkey" to kc.publicKeyHex, "sig" to "")).error,
        )
        assertEquals(
            SigningError.SignerKindChanged(SignerActor.REMOTE_SIGNER, 7, 1),
            verify(jsonObjectOf("pubkey" to kc.publicKeyHex, "sig" to "aa", "kind" to 7)).error,
        )
        assertEquals(
            SigningError.SignerContentChanged(SignerActor.REMOTE_SIGNER),
            verify(jsonObjectOf("pubkey" to kc.publicKeyHex, "sig" to "aa", "content" to "anders")).error,
        )
        assertEquals(
            SigningError.SignerBadSig(SignerActor.REMOTE_SIGNER),
            verify(jsonObjectOf("pubkey" to kc.publicKeyHex, "sig" to "00".repeat(64), "id" to JsonPrimitive("x"))).error,
        )
        assertEquals(
            SigningError.WrongAccount,
            verify(jsonObjectOf("pubkey" to KeyPair.generate().publicKeyHex, "sig" to "aa")).error,
        )
    }

    @Test
    fun modeWithoutKeyAndWebOnlyAreTyped() = runBlocking {
        val id = TestIdentity()
        id.prefs.setString(IdentityKeys.SIGNING_MODE, "amber")
        assertEquals(SigningError.ModeNoKey(SigningMode.AMBER), (runCatching { id.signing.activeSigner() }.exceptionOrNull() as SigningException).error)
        id.prefs.setString(IdentityKeys.SIGNING_MODE, "nip46")
        assertEquals(SigningError.ModeNoKey(SigningMode.NIP46), (runCatching { id.signing.activeSigner() }.exceptionOrNull() as SigningException).error)
        id.signing.restoreNip07(KeyPair.generate().npub)
        val e = runCatching { id.signing.signEvent(1, emptyList(), "x") }.exceptionOrNull() as SigningException
        assertEquals(SigningError.Nip07WebOnly, e.error)
        assertEquals("NIP-07 gibt es nur im Browser.", e.message)
    }
}
