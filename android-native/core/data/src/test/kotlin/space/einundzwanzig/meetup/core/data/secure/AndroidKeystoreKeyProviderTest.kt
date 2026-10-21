package space.einundzwanzig.meetup.core.data.secure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyPairGenerator
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** The key provider's "never replace an existing key" rules, on a fake Keystore. */
class AndroidKeystoreKeyProviderTest {

    private class FakeKeystore : KeystoreBackend {
        val keys = ConcurrentHashMap<String, Key>()
        val generated = AtomicInteger()
        val lookups = AtomicInteger()

        /** Thrown by [getKey] for an existing alias (e.g. a Keystore IPC failure). */
        @Volatile
        var getKeyFailure: GeneralSecurityException? = null

        /** Thrown by [containsAlias] (Keystore cannot be queried). */
        @Volatile
        var queryFailure: GeneralSecurityException? = null

        override fun containsAlias(alias: String): Boolean {
            queryFailure?.let { throw it }
            return keys.containsKey(alias)
        }

        override fun getKey(alias: String): Key? {
            lookups.incrementAndGet()
            getKeyFailure?.let { throw it }
            return keys[alias]
        }

        override fun generateAes256Gcm(alias: String): SecretKey {
            generated.incrementAndGet()
            // Not synchronized: concurrent generations overlap unless the provider serializes them.
            Thread.sleep(20)
            return SecretKeySpec(ByteArray(32) { generated.get().toByte() }, "AES").also { keys[alias] = it }
        }
    }

    private val alias = AndroidKeystoreKeyProvider.KEY_ALIAS

    @Test
    fun absentAliasIsGeneratedOnceByEncryptionKey() {
        val keystore = FakeKeystore()
        val provider = AndroidKeystoreKeyProvider(alias, keystore)

        val first = provider.encryptionKey()
        assertSame(first, provider.encryptionKey())
        assertSame(first, provider.decryptionKey())
        assertEquals(1, keystore.generated.get())
        assertSame(first, keystore.keys[alias])
    }

    @Test
    fun concurrentFirstUseGeneratesExactlyOneKey() {
        val keystore = FakeKeystore()
        val provider = AndroidKeystoreKeyProvider(alias, keystore)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..8).map { pool.submit<SecretKey> { start.await(); provider.encryptionKey() } }
            start.countDown()
            val keys = results.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, keystore.generated.get())
            assertEquals(1, keys.distinct().size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun existingKeyIsLoadedNotRegenerated() {
        val keystore = FakeKeystore()
        val existing = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        keystore.keys[alias] = existing
        val provider = AndroidKeystoreKeyProvider(alias, keystore)

        assertSame(existing, provider.encryptionKey())
        assertEquals(0, keystore.generated.get())
    }

    @Test
    fun existingAliasThatCannotBeLoadedThrowsAndIsNotReplaced() {
        val keystore = FakeKeystore()
        val existing = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        keystore.keys[alias] = existing
        keystore.getKeyFailure = UnrecoverableKeyException("keystore2 busy")
        val provider = AndroidKeystoreKeyProvider(alias, keystore)

        assertThrows(UnrecoverableKeyException::class.java) { provider.encryptionKey() }
        assertThrows(UnrecoverableKeyException::class.java) { provider.decryptionKey() }
        assertEquals(0, keystore.generated.get())
        assertSame(existing, keystore.keys[alias])

        keystore.getKeyFailure = null
        assertSame("works again once the Keystore recovers", existing, provider.decryptionKey())
    }

    @Test
    fun existingAliasHoldingANonSecretKeyThrowsAndIsNotReplaced() {
        val keystore = FakeKeystore()
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private
        keystore.keys[alias] = rsa
        val provider = AndroidKeystoreKeyProvider(alias, keystore)

        assertThrows(UnrecoverableKeyException::class.java) { provider.encryptionKey() }
        assertEquals(0, keystore.generated.get())
        assertSame(rsa, keystore.keys[alias])
    }

    @Test
    fun keystoreQueryFailureThrowsAndDoesNotGenerate() {
        val keystore = FakeKeystore()
        keystore.queryFailure = KeyStoreException("Keystore not initialized")
        val provider = AndroidKeystoreKeyProvider(alias, keystore)

        assertThrows(KeyStoreException::class.java) { provider.encryptionKey() }
        assertEquals(0, keystore.generated.get())
    }

    @Test
    fun decryptionKeyNeverCreatesAKey() {
        val keystore = FakeKeystore()
        val provider = AndroidKeystoreKeyProvider(alias, keystore)

        assertThrows(KeyMissingException::class.java) { provider.decryptionKey() }
        assertThrows(KeyMissingException::class.java) { provider.decryptionKey() }
        assertEquals(0, keystore.generated.get())
        assertEquals(emptyMap<String, Key>(), keystore.keys)
    }

    @Test
    fun keyIsCachedUntilInvalidated() {
        val keystore = FakeKeystore()
        val original = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        keystore.keys[alias] = original
        val provider = AndroidKeystoreKeyProvider(alias, keystore)

        assertSame(original, provider.decryptionKey())
        provider.decryptionKey()
        provider.encryptionKey()
        assertEquals(1, keystore.lookups.get())

        val replaced = SecretKeySpec(ByteArray(32) { 2 }, "AES")
        keystore.keys[alias] = replaced
        assertSame("cached handle", original, provider.decryptionKey())
        provider.invalidate()
        assertSame("reloaded after invalidate", replaced, provider.decryptionKey())
        assertEquals(2, keystore.lookups.get())
    }
}
