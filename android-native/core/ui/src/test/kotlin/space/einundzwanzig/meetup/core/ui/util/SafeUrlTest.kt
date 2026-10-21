package space.einundzwanzig.meetup.core.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test
import space.einundzwanzig.meetup.core.ui.nostr.NpubFormat

/** Expectations produced with the Dart `launchHttpUrl` check (Uri.tryParse, scheme + host). */
class SafeUrlTest {

    @Test
    fun `scheme whitelist matches the Dart launcher`() {
        val cases = linkedMapOf(
            "https://njump.me/npub1x" to true,
            "http://a.b" to true,
            " HTTPS://Example.com/x " to true,
            "javascript:alert(1)" to false,
            "intent://x#Intent;end" to false,
            "file:///etc/passwd" to false,
            "https://" to false,
            "https:///path" to false,
            "http:foo" to false,
            "ftp://a.b" to false,
            "tel:123" to false,
            "https://user:pw@host.com:8080/p?q#f" to true,
            "https://[::1]:80/" to true,
            "https://host:abc/" to false,
            "https://exa mple.com" to true,
            "https://a.com/a b" to true,
            "mailto:a@b.c" to false,
            "nostr:npub1" to false,
            "" to false,
            "https://ä.de" to true,
            "https://host:/x" to true,
            "https://@/x" to false,
            "https://[]/x" to false,
        )
        cases.forEach { (url, allowed) -> assertEquals("'$url'", allowed, SafeUrl.isHttpUrl(url)) }
    }

    @Test
    fun `host extraction`() {
        assertEquals("host.com", SafeUrl.httpHost("https://user:pw@host.com:8080/p"))
        assertEquals("[::1]", SafeUrl.httpHost("https://[::1]:80/"))
        assertEquals("njump.me", SafeUrl.httpHost("https://njump.me"))
    }

    @Test
    fun `npub shortening keeps start and end`() {
        assertEquals("npub1dzu6c…ysl4qd9z", NpubFormat.shorten("npub1dzu6ceugmm7xqs2drhwl5jdyhwrhc3ezgr0w6tmmvqzjmn5ac0ysl4qd9z"))
        assertEquals("npub1short", NpubFormat.shorten("  npub1short  "))
        assertEquals("1234567890123456789", NpubFormat.shorten("1234567890123456789"))
        assertEquals("1234567890…34567890", NpubFormat.shorten("12345678901234567890"))
        assertEquals("npub1dzu6ceu…ysl4qd9z", NpubFormat.shorten("npub1dzu6ceugmm7xqs2drhwl5jdyhwrhc3ezgr0w6tmmvqzjmn5ac0ysl4qd9z", head = 12))
    }

    @Test
    fun `NostrService shortenNpub variant`() {
        val npub = "npub1dzu6ceugmm7xqs2drhwl5jdyhwrhc3ezgr0w6tmmvqzjmn5ac0ysl4qd9z"
        assertEquals("npub1dzu6ceu...ysl4qd9z", NpubFormat.shortenNpub(npub))
        assertEquals("12345678901234567890", NpubFormat.shortenNpub("12345678901234567890")) // < 21 unchanged
        assertEquals("123456789012...45678901", NpubFormat.shortenNpub("123456789012345678901"))
        assertEquals("https://njump.me/npub1abc", NpubFormat.profileUrl(" npub1abc "))
    }
}
