package space.einundzwanzig.meetup.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class NetworkConfigTest {

    @Serializable
    data class Profile(val name: String, val nip05: String? = null, val about: String? = null)

    @Test
    fun appJsonIgnoresUnknownKeys() {
        val p = AppJson.decodeFromString(Profile.serializer(), """{"name":"a","lud16":"x@y","extra":{"n":1}}""")
        assertEquals(Profile("a"), p)
    }

    @Test
    fun appJsonOmitsNullsAndReadsAbsentNullablesAsNull() {
        assertEquals("""{"name":"a"}""", AppJson.encodeToString(Profile.serializer(), Profile("a")))
        assertEquals("""{"name":"a","nip05":"b"}""", AppJson.encodeToString(Profile.serializer(), Profile("a", "b")))
        assertNull(AppJson.decodeFromString(Profile.serializer(), """{"name":"a","nip05":null}""").nip05)
    }

    @Test
    fun appJsonIsStrict() {
        assertThrows(SerializationException::class.java) {
            AppJson.decodeFromString(Profile.serializer(), """{name:"a"}""")
        }
        assertThrows(SerializationException::class.java) {
            AppJson.decodeFromString(Profile.serializer(), """{"name":a}""")
        }
    }

    @Test
    fun userAgentFormat() {
        assertEquals("Einundzwanzig-Meetup-App/2.0.0 (Android)", UserAgent.forVersion("2.0.0"))
        assertEquals("Einundzwanzig-Meetup-App/2.0.0-debug (Android)", UserAgent.forVersion(" 2.0.0-debug "))
        assertEquals("Einundzwanzig-Meetup-App/unknown (Android)", UserAgent.forVersion(null))
        assertEquals("Einundzwanzig-Meetup-App/unknown (Android)", UserAgent.forVersion("  "))
        assertEquals("Einundzwanzig-Meetup-App/2.0__ (Android)", UserAgent.forVersion("2.0 \u00e4\n"))
    }

    @Test
    fun cleartextPolicyMatchesNetworkSecurityConfig() {
        listOf(
            "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion",
            "FOO.ONION",
            "onion",
            "umbrel.local",
            "local",
            "127.0.0.1",
            "localhost",
            "localhost.", // MempoolHosts accepts the trailing-dot custom URL.
        ).forEach { assertTrue(it, CleartextPolicy.isCleartextPermitted(it)) }

        listOf(
            "mempool.space",
            "foo.onion.example.com",
            "192.168.1.10",
            "10.0.0.1",
            "127.0.0.2",
            "sub.localhost",
            "notlocal",
            "evil-onion",
        ).forEach { assertFalse(it, CleartextPolicy.isCleartextPermitted(it)) }
    }

    @Test
    fun sharedClientConfiguration() {
        val client = buildAppOkHttpClient("UA")
        assertEquals(TimeUnit.SECONDS.toMillis(45), client.connectTimeoutMillis.toLong())
        assertEquals(TimeUnit.SECONDS.toMillis(45), client.readTimeoutMillis.toLong())
        assertEquals(TimeUnit.SECONDS.toMillis(45), client.writeTimeoutMillis.toLong())
        assertEquals("no client-wide call timeout", 0, client.callTimeoutMillis)
        assertEquals(0, client.pingIntervalMillis)
        assertTrue(client.dispatcher.maxRequestsPerHost >= 6)
        assertNull(client.cache)
        assertTrue(client.followRedirects)
    }
}
