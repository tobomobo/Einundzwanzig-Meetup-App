package space.einundzwanzig.meetup.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoints
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19

/** Exercises the actual Hilt hook, Keystore, signing service and proof repository together. */
@RunWith(AndroidJUnit4::class)
class IdentityProofDeviceTest {
    @Test fun firstKeyAndIdentitySwitchFenceTheActualProofRepository(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPoints.get(context.applicationContext, DeviceParityEntryPoint::class.java)
        withTimeout(60_000) { entry.migration().gate.await() }
        // Preserve upgrade fixtures and existing test identities. CI preview installs are empty.
        assumeFalse("Requires an empty test app, never replace an existing identity", entry.keys().hasKey())
        val before = entry.preferences().getAll()
        suspend fun newIdentity(): String {
            val pair = KeyPair.generate()
            val npub = Nip19.encodeNpub(pair.publicKeyHex)
            entry.keys().saveKeys(Nip19.encodeNsec(pair.privateKeyHex), npub, pair.privateKeyHex)
            return npub
        }
        try {
            val a = newIdentity()
            assertEquals(a, entry.signing().npub())
            assertTrue(entry.platformProofs().createProof("twitter", "device_identity_a").success)
            assertEquals("device_identity_a", entry.platformProofs().getSavedProofs().single().username)
            val b = newIdentity()
            assertNotEquals(a, b)
            assertEquals(b, entry.signing().npub())
            assertTrue(entry.platformProofs().getSavedProofs().isEmpty())
            assertTrue(entry.platformProofs().getProofsForPublishing().isEmpty())
            assertTrue(entry.platformProofs().createProof("twitter", "device_identity_b").success)
            assertEquals("device_identity_b", entry.platformProofs().getProofsForPublishing().values.single().username)
        } finally {
            entry.keys().deleteKeys()
            assertTrue(entry.preferences().edit {
                clear()
                before.forEach { (key, value) -> set(key, value) }
            })
        }
    }
}
