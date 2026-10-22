package space.einundzwanzig.meetup.data.community.v4v

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.log.AppLogger
import java.net.InetAddress
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class V4vServiceTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private lateinit var service: DefaultV4vService

    @Before
    fun setUp() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        service = DefaultV4vService(
            client = client,
            log = AppLogger,
            metadataUrl = { user, domain -> "http://127.0.0.1:${server.port}/$domain/.well-known/lnurlp/$user" },
            timeout = 5.seconds,
        )
    }

    @After
    fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()

    private fun callback(path: String = "/cb") = "http://127.0.0.1:${server.port}$path"

    private fun meta(extra: String = "", callback: String = callback()) =
        """{"tag":"payRequest","callback":"$callback","minSendable":1000,"maxSendable":100000000$extra}"""

    private fun create(amount: Long, address: String = V4V_LIGHTNING_ADDRESS, comment: String? = null) =
        runBlocking { service.createInvoice(amount, address, comment) }

    @Test
    fun successRequestsMetadataThenInvoice() {
        server.enqueue(json(meta(""","commentAllowed":5""")))
        server.enqueue(json("""{"pr":"lnbc210n1test","routes":[]}"""))

        val result = create(21, comment = V4V_DEFAULT_COMMENT)

        assertEquals(V4vResult.Success("lnbc210n1test"), result)
        assertTrue(result.ok)
        val metaReq = server.takeRequest()
        assertEquals("/timecatcher.lnbits.de/.well-known/lnurlp/wolpertinger1", metaReq.url.encodedPath)
        val invReq = server.takeRequest()
        // "V4V – 21Meetup" cut to 5 UTF-16 units = "V4V –", form-encoded like Uri.encodeQueryComponent.
        assertEquals("/cb?amount=21000&comment=V4V+%E2%80%93", invReq.target)
    }

    @Test
    fun noCommentWhenNotAllowed() {
        server.enqueue(json(meta()))
        server.enqueue(json("""{"pr":"lnbc1"}"""))
        assertEquals(V4vResult.Success("lnbc1"), create(21, comment = V4V_DEFAULT_COMMENT))
        server.takeRequest()
        assertEquals("/cb?amount=21000", server.takeRequest().target)
    }

    @Test
    fun callbackWithQueryUsesAmpersand() {
        server.enqueue(json(meta(callback = callback("/cb?id=1"))))
        server.enqueue(json("""{"pr":"lnbc1"}"""))
        create(21)
        server.takeRequest()
        assertEquals("/cb?id=1&amount=21000", server.takeRequest().target)
    }

    @Test
    fun invalidAmountAndAddressNeedNoNetwork() {
        assertEquals(V4vResult.Failure(V4vError.INVALID_AMOUNT), create(0))
        assertEquals(V4vResult.Failure(V4vError.INVALID_AMOUNT), create(-5))
        for (address in listOf("nobody", "a@b@c", "@domain", "user@", "")) {
            assertEquals(address, V4vResult.Failure(V4vError.INVALID_ADDRESS), create(21, address))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun metadataNon200IsUnreachable() {
        server.enqueue(json("{}", code = 404))
        assertEquals(V4vResult.Failure(V4vError.UNREACHABLE), create(21))
    }

    @Test
    fun wrongTagOrMissingCallbackIsNotLnurlp() {
        server.enqueue(json("""{"tag":"withdrawRequest","callback":"x"}"""))
        assertEquals(V4vResult.Failure(V4vError.NOT_LNURLP), create(21))
        server.enqueue(json("""{"tag":"payRequest"}"""))
        assertEquals(V4vResult.Failure(V4vError.NOT_LNURLP), create(21))
        server.enqueue(json("""{"tag":"payRequest","callback":null}"""))
        assertEquals(V4vResult.Failure(V4vError.NOT_LNURLP), create(21))
    }

    @Test
    fun limitsAreCheckedInMsat() {
        server.enqueue(json(meta()))
        assertEquals(V4vResult.Failure(V4vError.ABOVE_MAX), create(100_001))
        server.enqueue(json("""{"tag":"payRequest","callback":"${callback()}","minSendable":50000}"""))
        assertEquals(V4vResult.Failure(V4vError.BELOW_MIN), create(49))
        // Exactly the limits are allowed.
        server.enqueue(json(meta()))
        server.enqueue(json("""{"pr":"lnbcmax"}"""))
        assertEquals(V4vResult.Success("lnbcmax"), create(100_000))
    }

    @Test
    fun defaultLimitsApplyWhenMissing() {
        // min 1000 msat, max 100_000_000_000 msat
        server.enqueue(json("""{"tag":"payRequest","callback":"${callback()}"}"""))
        assertEquals(V4vResult.Failure(V4vError.ABOVE_MAX), create(100_000_001))
        server.enqueue(json("""{"tag":"payRequest","callback":"${callback()}","minSendable":1000.0}"""))
        server.enqueue(json("""{"pr":"lnbc1"}"""))
        assertEquals(V4vResult.Success("lnbc1"), create(1))
    }

    @Test
    fun nonNumericLimitIsUnreachable() {
        // Dart: `(meta['minSendable'] as num?)` throws → catch-all → unreachable.
        server.enqueue(json("""{"tag":"payRequest","callback":"${callback()}","minSendable":"1000"}"""))
        assertEquals(V4vResult.Failure(V4vError.UNREACHABLE), create(21))
    }

    @Test
    fun invoiceErrors() {
        server.enqueue(json(meta()))
        server.enqueue(json("{}", code = 500))
        assertEquals(V4vResult.Failure(V4vError.INVOICE_FAILED), create(21))

        server.enqueue(json(meta()))
        server.enqueue(json("""{"status":"ERROR","reason":"Amount too low"}"""))
        assertEquals(V4vResult.Failure("Amount too low"), create(21))

        server.enqueue(json(meta()))
        server.enqueue(json("""{"status":"error"}"""))
        assertEquals(V4vResult.Failure(V4vError.INVOICE_FAILED), create(21))

        server.enqueue(json(meta()))
        server.enqueue(json("""{"pr":""}"""))
        assertEquals(V4vResult.Failure(V4vError.INVOICE_FAILED), create(21))

        server.enqueue(json(meta()))
        server.enqueue(json("""{"routes":[]}"""))
        assertEquals(V4vResult.Failure(V4vError.INVOICE_FAILED), create(21))
    }

    @Test
    fun malformedJsonIsUnreachable() {
        server.enqueue(json("<html>"))
        assertEquals(V4vResult.Failure(V4vError.UNREACHABLE), create(21))
        server.enqueue(json("[1,2]"))
        assertEquals(V4vResult.Failure(V4vError.UNREACHABLE), create(21))
        server.enqueue(json(meta()))
        server.enqueue(json("not json"))
        assertEquals(V4vResult.Failure(V4vError.UNREACHABLE), create(21))
    }

    @Test
    fun timeoutIsUnreachable() {
        val slow = DefaultV4vService(
            client = client,
            log = AppLogger,
            metadataUrl = { user, domain -> "http://127.0.0.1:${server.port}/$domain/$user" },
            timeout = 200.milliseconds,
        )
        server.enqueue(MockResponse.Builder().code(200).body(meta()).headersDelay(2, java.util.concurrent.TimeUnit.SECONDS).build())
        val result = runBlocking { slow.createInvoice(21) }
        assertEquals(V4vResult.Failure(V4vError.UNREACHABLE), result)
        assertFalse(result.ok)
    }

    @Test
    fun errorKindsMatchTheScreenMapping() {
        assertEquals(V4vErrorKind.InvalidAmount, V4vResult.Failure("invalid_amount").kind)
        assertEquals(V4vErrorKind.BelowMin, V4vResult.Failure("below_min").kind)
        assertEquals(V4vErrorKind.AboveMax, V4vResult.Failure("above_max").kind)
        assertEquals(V4vErrorKind.Unreachable, V4vResult.Failure("unreachable").kind)
        for (code in listOf("invalid_address", "not_lnurlp", "invoice_failed", "Amount too low")) {
            assertEquals(V4vErrorKind.Generic, V4vResult.Failure(code).kind)
        }
    }
}
