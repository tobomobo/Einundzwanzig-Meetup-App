package space.einundzwanzig.meetup.core.common.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Expected decisions computed with Dart 3.13.5 `launchHttpUri(Uri.tryParse(url.trim()))`. */
class SafeUrlTest {

    @Test
    fun `allowed like in Dart`() {
        val cases = listOf(
            "https://example.com" to "example.com",
            "HTTPS://Example.COM/Path" to "example.com",
            " https://a.b " to "a.b",
            "https://user:pw@host.tld:8080/p?q=1#f" to "host.tld",
            "https://[::1]/x" to "::1",
            "https://%41.com/" to "a.com",
            "http://a.com:99999/" to "a.com",
            "https://a.com/%zz" to "a.com",
            "https://ä.de/" to "ä.de",
            "https://a.com/x y" to "a.com",
            "https://a.com/\u0000" to "a.com",
            "Https://a.com" to "a.com",
            "https://a..com" to "a..com",
            "https://a.com:/" to "a.com",
            "http://portal.einundzwanzig.space/api" to "portal.einundzwanzig.space",
        )
        for ((url, host) in cases) {
            val safe = SafeUrl.httpUrlOrNull(url)
            assertEquals(url, host, safe?.host)
        }
        assertEquals("https", SafeUrl.httpUrlOrNull("HTTPS://Example.COM/Path")!!.scheme)
        assertEquals("https://a.b", SafeUrl.httpUrlOrNull(" https://a.b ")!!.url)
        assertEquals("https://a.com/x%20y", SafeUrl.httpUrlOrNull("https://a.com/x y")!!.url)
        assertEquals("https://a.com/%00", SafeUrl.httpUrlOrNull("https://a.com/\u0000")!!.url)
    }

    @Test
    fun `rejected like in Dart`() {
        val rejected = listOf(
            "https:///path", "http://", "https:example.com", "https://a.com:abc/", "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end", "file:///etc/passwd", "ftp://a.com", "nostr:npub1abc",
            "//a.com/x", "a.com", "", "https://[zz]/", "https://@/", "https://:80/", "h ttps://a.com",
            "https ://a.com", "\u0000https://a.com", "tel:+43123", "sms:123", "content://x/y", "data:text/html,<b>",
            "lightning:lnbc1", "nostrsigner:abc",
        )
        for (url in rejected) assertNull(url, SafeUrl.httpUrlOrNull(url))
    }

    @Test
    fun `stricter than Dart for whitespace, controls and backslashes in the authority`() {
        // Dart accepts these (it percent-encodes or rewrites the authority); we refuse them.
        for (url in listOf("https://exa mple.com/", "https://a.com\nb", "https://a.com\\@b.com", "http:\\\\a.com")) {
            assertNull(url, SafeUrl.httpUrlOrNull(url))
        }
    }

    @Test
    fun `hostForLog never returns more than the host`() {
        assertEquals("nsec.app", SafeUrl.hostForLog("https://nsec.app/approve/SECRET-TOKEN?x=1"))
        assertEquals("unbekannt", SafeUrl.hostForLog("kaputt"))
        assertEquals("unbekannt", SafeUrl.hostForLog("https:///nohost"))
        assertEquals("scan", SafeUrl.hostForLog("intent://scan/#x"))
    }
}
