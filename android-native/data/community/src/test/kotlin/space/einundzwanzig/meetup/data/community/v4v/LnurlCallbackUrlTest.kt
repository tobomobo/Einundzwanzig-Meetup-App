package space.einundzwanzig.meetup.data.community.v4v

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 11 §10.4 "LNURL URL builder" vectors plus the V4V amount parsing of `_donate()`. */
class LnurlCallbackUrlTest {

    @Test
    fun amountSeparator() {
        assertEquals("https://x/cb?amount=21000", buildLnurlCallbackUrl("https://x/cb", 21_000, null, 0))
        assertEquals("https://x/cb?id=1&amount=21000", buildLnurlCallbackUrl("https://x/cb?id=1", 21_000, null, 0))
    }

    @Test
    fun commentIsTruncatedOnUtf16UnitsAndFormEncoded() {
        assertEquals(
            "https://x/cb?amount=21000&comment=V4V+%E2%80%93",
            buildLnurlCallbackUrl("https://x/cb", 21_000, "V4V – 21Meetup", 5),
        )
        assertEquals(
            "https://x/cb?amount=21000&comment=V4V+%E2%80%93+21Meetup",
            buildLnurlCallbackUrl("https://x/cb", 21_000, V4V_DEFAULT_COMMENT, 255),
        )
    }

    @Test
    fun noCommentParam() {
        assertEquals("https://x/cb?amount=21000", buildLnurlCallbackUrl("https://x/cb", 21_000, V4V_DEFAULT_COMMENT, 0))
        assertEquals("https://x/cb?amount=21000", buildLnurlCallbackUrl("https://x/cb", 21_000, "", 10))
        assertEquals("https://x/cb?amount=21000", buildLnurlCallbackUrl("https://x/cb", 21_000, null, 10))
    }

    @Test
    fun zapRequestReplacesComment() {
        // news_zap_service.dart step 4: `&nostr=` with the zap request, never a comment then.
        assertEquals(
            "https://x/cb?id=1&amount=2100000&nostr=%7B%22kind%22%3A9734%2C%22content%22%3A%22%22%7D",
            buildLnurlCallbackUrl("https://x/cb?id=1", 2_100_000, "Titel", 255, nostr = """{"kind":9734,"content":""}"""),
        )
        assertEquals(
            "https://x/cb?amount=21000&nostr=a+b",
            buildLnurlCallbackUrl("https://x/cb", 21_000, null, 0, nostr = "a b"),
        )
        // No zap request (allowsNostr != true): the title goes as comment, if allowed.
        assertEquals(
            "https://x/cb?amount=21000&comment=Tit",
            buildLnurlCallbackUrl("https://x/cb", 21_000, "Titel", 3, nostr = null),
        )
    }

    @Test
    fun defaultCommentUsesEnDash() {
        assertEquals("V4V – 21Meetup", V4V_DEFAULT_COMMENT)
        assertEquals("wolpertinger1@timecatcher.lnbits.de", V4V_LIGHTNING_ADDRESS)
    }

    @Test
    fun parseAmountLikeDonate() {
        assertEquals(21L, parseV4vAmount("21"))
        assertEquals(2100L, parseV4vAmount(" 2.100 "))
        assertEquals(21000L, parseV4vAmount("21,000"))
        assertEquals(0L, parseV4vAmount(""))
        assertEquals(0L, parseV4vAmount("abc"))
        assertEquals(0L, parseV4vAmount("1 000"))
        assertEquals(0L, parseV4vAmount("99999999999999999999"))
        assertEquals(-5L, parseV4vAmount("-5"))
    }
}
