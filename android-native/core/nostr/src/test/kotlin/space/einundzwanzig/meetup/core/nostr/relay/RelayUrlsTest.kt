package space.einundzwanzig.meetup.core.nostr.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Both relay URL validators of spec 07 §8.2. Expected values were produced with Dart 3.13
 * (`Uri.tryParse`, `RelayConfig.isWellFormedRelayUrl`, `NostrProfileLookup.relayUrl`).
 */
class RelayUrlsTest {

    @Test
    fun wellFormedMatchesDart() {
        val cases = linkedMapOf(
            "wss://relay.damus.io" to true,
            " wss://nos.lol/ " to true,
            "ws://relay.example" to false,
            "WSS://relay.example" to false,
            "wss://localhost" to false,
            "wss://.relay.com" to false,
            "wss://relay.com." to false,
            "wss://relay.com/path" to false,
            "wss://relay.com?x=1" to false,
            "wss://relay.com#a" to false,
            "wss://relay.com:abc" to false,
            "wss://user:pw@relay.com:7447" to true,
            "wss://re lay.com" to true,
            "wss://relay.com/./" to true,
            "wss://" to false,
            "wss:///relay.com" to false,
            "wss://[::1]" to false,
            "wss://[::ffff:1.2.3.4]" to true,
            "wss://Test.nostr.band" to true,
            "https://relay.com" to false,
            "wss://relay.com//" to false,
            "wss://relay.com:" to true,
            "wss://relay..com" to true,
            "wss://relay.com/%2E/" to true,
        )
        for ((input, expected) in cases) {
            assertEquals("[$input]", expected, RelayUrls.isWellFormedRelayUrl(input))
            assertEquals("[$input]", expected, RelayConfig.isWellFormedRelayUrl(input))
        }
    }

    @Test
    fun lookupNormalisationMatchesDart() {
        // input → (relayUrl(v), relayUrl(v, discovered: true))
        val cases = listOf(
            Triple("wss://Relay.Example/", "wss://relay.example", "wss://relay.example"),
            Triple("WSS://relay.example", "wss://relay.example", "wss://relay.example"),
            Triple("wss://relay.example:443/", "wss://relay.example:443", "wss://relay.example:443"),
            Triple("wss://relay.example:0443", "wss://relay.example:443", "wss://relay.example:443"),
            Triple("wss://relay.example/path", "wss://relay.example/path", "wss://relay.example/path"),
            Triple("wss://relay.example/?", "wss://relay.example/?", "wss://relay.example/?"),
            Triple("wss://relay.example?a=1", "wss://relay.example?a=1", "wss://relay.example?a=1"),
            Triple("wss://relay.example/a%41b", "wss://relay.example/aAb", "wss://relay.example/aAb"),
            Triple("wss://relay.example/a b", "wss://relay.example/a%20b", "wss://relay.example/a%20b"),
            Triple("wss://relay.example.", "wss://relay.example.", "wss://relay.example."),
            Triple("wss://re lay.example", "wss://re%20lay.example", "wss://re%20lay.example"),
            Triple("wss://0x7f.0.0.1", "wss://0x7f.0.0.1", null),
            Triple("wss://+1.2.3.4", "wss://+1.2.3.4", "wss://+1.2.3.4"),
            Triple("wss://1.2.3.4", "wss://1.2.3.4", "wss://1.2.3.4"),
            Triple("wss://8.8.8.8:7000/", "wss://8.8.8.8:7000", "wss://8.8.8.8:7000"),
            Triple("wss://relay.example:", "wss://relay.example", "wss://relay.example"),
            Triple("wss://ünï.example", "wss://%C3%BCn%C3%AF.example", "wss://%C3%BCn%C3%AF.example"),
            Triple("wss://relay.example/~x", "wss://relay.example/~x", "wss://relay.example/~x"),
            Triple("wss://relay.example//", "wss://relay.example//", "wss://relay.example//"),
            Triple(" wss://relay.example ", "wss://relay.example", "wss://relay.example"),
            Triple("wss://relay.example/a/../b", "wss://relay.example/b", "wss://relay.example/b"),
            Triple("wss://relay.example/./", "wss://relay.example", "wss://relay.example"),
            Triple("wss://[::1]:443", "wss://[::1]:443", null),
            Triple("wss://relay.example:80", "wss://relay.example:80", "wss://relay.example:80"),
        )
        for ((input, plain, discovered) in cases) {
            assertEquals("[$input]", plain, RelayUrls.normalize(input))
            assertEquals("[$input] discovered", discovered, RelayUrls.normalize(input, discovered = true))
        }
    }

    @Test
    fun discoveredRelaysRejectLocalAndPrivateHosts() {
        // spec 07 §10.7 case 16 and case 11
        val rejected = listOf(
            "127.1", "10.1", "192.168.1.1", "172.16.0.1", "169.254.1.1", "100.64.0.1", "0.0.0.0",
            "999.1.1.1", "[::1]", "relay.local", "relay.local.", "localhost.localdomain", "127.0.0.1.",
        )
        for (host in rejected) assertNull(host, RelayUrls.normalize("wss://$host", discovered = true))
        assertNull(RelayUrls.normalize("ws://insecure.example", discovered = true))
        assertNull(RelayUrls.normalize("wss://127.0.0.1", discovered = true))
        assertNull(RelayUrls.normalize("wss://10.0.0.1", discovered = true))
        assertNull(RelayUrls.normalize("wss://user:pass@host.example", discovered = true))
        assertNull(RelayUrls.normalize("wss://localhost", discovered = true))
        assertNull(RelayUrls.normalize("wss://relay.example#x"))
        assertEquals("wss://relay.example", RelayUrls.normalize("wss://relay.example/", discovered = true))
        assertEquals("wss://relay.example/path", RelayUrls.normalize("wss://relay.example/path", discovered = true))
        assertEquals("wss://relay0.example", RelayUrls.normalize("wss://relay0.example", discovered = true))
        // app relays may be local (only discovered ones are restricted)
        assertEquals("wss://localhost", RelayUrls.normalize("wss://localhost"))
    }

    private data class DartUriCase(val input: String, val wellFormed: Boolean, val plain: String?, val discovered: String?)

    /**
     * Backslashes, signed/hex/default ports, IPvFuture and IPv6 zone ids. Expected values were
     * produced with Dart 3.13.5 (`RelayConfig.isWellFormedRelayUrl`, `NostrProfileLookup.relayUrl`).
     */
    @Test
    fun uriEdgeCasesMatchDart() {
        val cases = listOf(
            DartUriCase("wss://relay.com\\x", false, "wss://relay.com/x", "wss://relay.com/x"),
            DartUriCase("wss://a.b\\c", false, "wss://a.b/c", "wss://a.b/c"),
            DartUriCase("wss://a.b\\c\\..\\d", false, "wss://a.b/d", "wss://a.b/d"),
            DartUriCase("wss://relay.com\\", true, "wss://relay.com", "wss://relay.com"),
            DartUriCase("wss://relay.example:-1", true, "wss://relay.example:-1", "wss://relay.example:-1"),
            DartUriCase("wss://a.b:+1", true, "wss://a.b:1", "wss://a.b:1"),
            DartUriCase("wss://a.b:0", true, "wss://a.b", "wss://a.b"),
            DartUriCase("wss://a.b:0x10", true, "wss://a.b:16", "wss://a.b:16"),
            DartUriCase("wss://a.b:-0", true, "wss://a.b", "wss://a.b"),
            DartUriCase("wss://[v1.x]", true, "wss://[v1.x]", null),
            DartUriCase("wss://[v1F.AB:c]", true, "wss://[v1F.AB:c]", null),
            DartUriCase("wss://[V1.x]", false, null, null),
            DartUriCase("wss://[v.x]", false, null, null),
            DartUriCase("wss://[v1.]", false, null, null),
            DartUriCase("wss://[fe80::1%25eth0]", false, "wss://[fe80::1%25eth0]", null),
            DartUriCase("wss://[fe80::1%eth0]", false, "wss://[fe80::1%25eth0]", null),
            DartUriCase("wss://[FE80::1%25ETH0]:-5", false, "wss://[fe80::1%25ETH0]:-5", null),
            DartUriCase("wss://[fe80::1%eth%410]", false, "wss://[fe80::1%25etha0]", null),
            DartUriCase("wss://[fe80::1%25e%zz]", false, null, null),
            DartUriCase("wss://[::1%25e th]", false, "wss://[::1%25e%20th]", null),
            DartUriCase("wss://a.b?x\\y", false, "wss://a.b?x%5Cy", "wss://a.b?x%5Cy"),
            DartUriCase("wss://u\\s@a.b", false, "wss://u/s@a.b", null),
            DartUriCase("wss://a.b:+-1", false, null, null),
            DartUriCase("wss://a.b:9223372036854775807", true, "wss://a.b:9223372036854775807", "wss://a.b:9223372036854775807"),
            DartUriCase("wss://relay.example.com:443", true, "wss://relay.example.com:443", "wss://relay.example.com:443"),
            DartUriCase("wss://relay.example.com/", true, "wss://relay.example.com", "wss://relay.example.com"),
        )
        for (c in cases) {
            assertEquals("[${c.input}] wellFormed", c.wellFormed, RelayUrls.isWellFormedRelayUrl(c.input))
            assertEquals("[${c.input}] plain", c.plain, RelayUrls.normalize(c.input))
            assertEquals("[${c.input}] discovered", c.discovered, RelayUrls.normalize(c.input, discovered = true))
        }
        // Dart throws from Uri.tryParse for a port beyond 64 bit; the port is rejected here.
        assertEquals(false, RelayUrls.isWellFormedRelayUrl("wss://a.b:9223372036854775808"))
        assertNull(RelayUrls.normalize("wss://a.b:9223372036854775808"))
    }

    @Test
    fun dartIntTryParse() {
        assertEquals(16L, RelayUrls.dartIntTryParse("0x10"))
        assertEquals(-5L, RelayUrls.dartIntTryParse("-5"))
        assertEquals(5L, RelayUrls.dartIntTryParse(" 5"))
        assertEquals(1L, RelayUrls.dartIntTryParse("+1"))
        assertNull(RelayUrls.dartIntTryParse("1_0"))
        assertNull(RelayUrls.dartIntTryParse(""))
        assertNull(RelayUrls.dartIntTryParse("local"))
    }
}
