package space.einundzwanzig.meetup.data.mempool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 13 §10.2 `MempoolHttpException` vectors and the connection-test classification. */
class MempoolHttpExceptionTest {

    @Test
    fun cloudflareChallengeIsBlockedEvenWith200() {
        val e = MempoolHttpException(
            "/blocks/tip/height",
            200,
            "  <!DOCTYPE html>\n<html>\n   <head><title>Just a moment...</title>  ",
        )
        assertEquals("<!DOCTYPE html> <html> <head><title>Just a moment...</title>", e.snippet)
        assertTrue(e.looksBlocked)
    }

    @Test
    fun snippetIsCutAt120Chars() {
        val e = MempoolHttpException("/x", 500, "x".repeat(130))
        assertEquals(121, e.snippet.length)
        assertTrue(e.snippet.endsWith("…"))
    }

    @Test
    fun blockedStatuses() {
        for (code in listOf(403, 429, 503)) assertTrue("$code", MempoolHttpException("/x", code, "").looksBlocked)
        assertFalse(MempoolHttpException("/x", 500, "{\"error\":1}").looksBlocked)
        assertTrue(MempoolHttpException("/x", 500, "Error 1020 | Cloudflare").looksBlocked)
    }

    @Test
    fun message() {
        assertEquals("HTTP 500 bei /v1/prices — {…}", MempoolHttpException("/v1/prices", 500, "{…}").toString())
    }

    @Test
    fun hostTestClassification() {
        assertEquals(HostTestFailure.Blocked(403), HostTestFailure.of(MempoolHttpException("/x", 403, ""), "mempool.space"))
        assertEquals(HostTestFailure.Http(500), HostTestFailure.of(MempoolHttpException("/x", 500, ""), "mempool.space"))
        assertEquals(HostTestFailure.OnionUnreachable, HostTestFailure.of(java.net.UnknownHostException("x"), MempoolHosts.TOR))
        assertEquals(
            HostTestFailure.Other("UnknownHostException"),
            HostTestFailure.of(java.net.UnknownHostException("x"), "mempool.space"),
        )
    }
}
