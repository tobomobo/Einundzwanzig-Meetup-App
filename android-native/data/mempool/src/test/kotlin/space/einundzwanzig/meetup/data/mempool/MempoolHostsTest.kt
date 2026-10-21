package space.einundzwanzig.meetup.data.mempool

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Spec 13 §10.2 `normalize` / `looksValid` / `hostLabel` vectors (computed with Dart 3). */
class MempoolHostsTest {

    private val vectors = listOf(
        Triple("", "https://mempool.space", true),
        Triple("   ", "https://mempool.space", true),
        Triple("mempool.space", "https://mempool.space", true),
        Triple("https://mempool.space/", "https://mempool.space", true),
        Triple("https://mempool.space/api", "https://mempool.space", true),
        Triple("https://mempool.space/api/", "https://mempool.space", true),
        Triple("https://mempool.space/API//", "https://mempool.space", true),
        Triple("mempool.space/api/v1", "https://mempool.space/api/v1", true),
        Triple("umbrel.local:3006", "https://umbrel.local:3006", true),
        Triple("http://umbrel.local:3006/", "http://umbrel.local:3006", true),
        Triple(
            "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion",
            "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion",
            true,
        ),
        Triple("HTTPS://Mempool.Space", "https://HTTPS://Mempool.Space", false),
        Triple("localhost:8080", "https://localhost:8080", false),
        Triple("192.168.1.50:3006", "https://192.168.1.50:3006", true),
        Triple("https://node.example.com/mempool/api", "https://node.example.com/mempool", true),
        Triple("mempool", "https://mempool", false),
        Triple("https://[::1]:3006", "https://[::1]:3006", false),
        Triple("ftp://x.y", "https://ftp://x.y", false),
        Triple("https://", "https:", false),
        Triple("https://api", "https:", false),
        Triple("https://.", "https://.", true),
        Triple("foo.onion.example.com", "http://foo.onion.example.com", true),
        Triple("https://x.y//api//", "https://x.y", true),
        // Review vectors, checked against Dart 3.13 Uri.tryParse.
        Triple("https://[1.2]", "https://[1.2]", false),
        Triple("https://a@b@x.y", "https://a@b@x.y", false),
        Triple("https://x.y:80:90", "https://x.y:80:90", false),
        Triple("https://%2e", "https://%2e", true),
        Triple("https://x%2ey", "https://x%2ey", true),
        Triple("https://x.y/APİ", "https://x.y", true),
    )

    @Test
    fun normalizeVectors() {
        for ((raw, expected, _) in vectors) assertEquals("normalize('$raw')", expected, MempoolHosts.normalize(raw))
    }

    @Test
    fun looksValidVectors() {
        for ((raw, _, valid) in vectors) assertEquals("looksValid('$raw')", valid, MempoolHosts.looksValid(raw))
    }

    /**
     * Differential vectors generated with Dart 3.13 (`normalize` + `looksValid` of
     * `mempool_config.dart`): a hand-picked corpus of URI edge cases (IPv6/IPv4/zone/IPvFuture
     * literals, user info, ports, escapes, backslashes) plus 4000 seeded random inputs.
     * Format per line: `input<TAB>normalized<TAB>looksValid`.
     */
    @Test
    fun dartDifferentialVectors() {
        val stream = javaClass.classLoader!!.getResourceAsStream("dart_mempool_host_vectors.tsv")!!
        val lines = stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotEmpty() }
        assertEquals(4114, lines.size)
        val mismatches = lines.mapNotNull { line ->
            val (raw, normalized, valid) = line.split('\t')
            val n = MempoolHosts.normalize(raw)
            val v = MempoolHosts.looksValid(raw)
            if (n == normalized && v == valid.toBooleanStrict()) null else "'$raw': normalize=$n (Dart $normalized), looksValid=$v (Dart $valid)"
        }
        assertEquals(mismatches.take(20).joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun forLogDropsUserInfo() {
        assertEquals("https://node.local", MempoolHosts.forLog("https://user:pass@node.local"))
        assertEquals("https://node.local:3006", MempoolHosts.forLog("https://user:pass@node.local:3006"))
        assertEquals("https://x.y", MempoolHosts.forLog("https://a@b@x.y"))
        assertEquals("https://x.y/p@q", MempoolHosts.forLog("https://u@x.y/p@q"))
        assertEquals("https://x.y/p@q", MempoolHosts.forLog("https://x.y/p@q"))
        assertEquals(MempoolHosts.TOR, MempoolHosts.forLog(MempoolHosts.TOR))
        assertEquals(MempoolHosts.CLEARNET, MempoolHosts.forLog(MempoolHosts.CLEARNET))
    }

    @Test
    fun torHostIsTheNormalizedOnion() {
        assertEquals(MempoolHosts.TOR, MempoolHosts.normalize("mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"))
    }

    @Test
    fun hostLabel() {
        assertEquals("mempool.space", MempoolHosts.label("https://mempool.space"))
        assertEquals("mempoolhqx4i…o2r6ad.onion", MempoolHosts.label(MempoolHosts.TOR))
        assertEquals("mempool.emzy.de", MempoolHosts.label("https://mempool.emzy.de"))
        val h28 = "a".repeat(20) + ".example" // 28 chars
        assertEquals(28, h28.length)
        assertEquals(h28, MempoolHosts.label("https://$h28"))
        val h29 = "b" + h28
        assertEquals("${h29.take(12)}…${h29.takeLast(12)}", MempoolHosts.label("https://$h29"))
    }

    @Test
    fun timeouts() {
        assertEquals(45.seconds, MempoolHosts.timeoutFor(MempoolHosts.TOR))
        assertEquals(20.seconds, MempoolHosts.timeoutFor(MempoolHosts.CLEARNET))
        assertEquals(listOf("https://mempool.emzy.de", "https://mempool.ninja"), MempoolHosts.FALLBACKS)
    }
}
