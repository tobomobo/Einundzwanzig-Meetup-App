package space.einundzwanzig.meetup.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 11 §10.4 "RSS date" and "RSS item" vectors plus the §8.3 parser rules. */
class RssParserTest {

    private val now = 1_800_000_000L

    @Test
    fun `rss dates are read as UTC and ignore the zone suffix`() {
        assertEquals(1_792_226_373L, RssParser.parseDate("Sat, 17 Oct 2026 08:39:33 GMT", now))
        assertEquals(1_792_152_977L, RssParser.parseDate("Fri, 16 Oct 2026 12:16:17 GMT", now))
        assertEquals(1_704_067_200L, RssParser.parseDate("Mon, 01 Jan 2024 00:00 +0200", now))
    }

    @Test
    fun `empty or garbage dates fall back to now`() {
        assertEquals(now, RssParser.parseDate("", now))
        assertEquals(now, RssParser.parseDate("garbage", now))
        assertEquals(now, RssParser.parseDate("17 Oct 26 08:39", now))
    }

    @Test
    fun `date details - full month names, unknown month, upper case, overflow`() {
        assertEquals(1_792_226_373L, RssParser.parseDate("SATURDAY, 17 OCTOBER 2026 08:39:33 GMT", now))
        // Unknown month → January.
        assertEquals(1_704_067_200L, RssParser.parseDate("1 Foo 2024 00:00", now))
        // Day overflow like Dart DateTime.utc: 31 Feb 2024 = 2 Mar 2024.
        assertEquals(1_709_337_600L, RssParser.parseDate("31 Feb 2024 00:00:00", now))
        // Non-ASCII digits are not \d in Dart.
        assertEquals(now, RssParser.parseDate("١٧ Oct 2026 08:39", now))
    }

    private val liveItem = """
        <item><title>Die Ausnahme, das Fenster und die Uhr</title>
        <link>https://media.einundzwanzig.space/s/einundzwanzig-news/die-ausnahme-das-fenster-und-die-uhr-71bdfd</link>
        <guid isPermaLink="false">30023:68240d4932950242924434c5b118035a92448a9570caf1fde7aeca77afbc80e4:draft-1c569370-55a9-4ff8-b3c6-2020f697e0f9</guid>
        <pubDate>Sat, 17 Oct 2026 08:39:33 GMT</pubDate><author>Roger</author>
        <description>Ein Zoll, der sich selbst ausnimmt, …</description>
        <enclosure url="https://image.nostr.build/a181242b58e9a95dd7474a7d7fbe7a2c77a42a8ff26a841924ec4c8293c7bd54.jpg" type="image/jpeg" />
        <category>bitcoin</category></item>
    """.trimIndent()

    @Test
    fun `live sample item`() {
        val items = RssParser.parse("<rss><channel><title>Feed</title>$liveItem</channel></rss>", now)
        assertEquals(1, items.size)
        val a = items[0]
        assertEquals(
            "30023:68240d4932950242924434c5b118035a92448a9570caf1fde7aeca77afbc80e4:draft-1c569370-55a9-4ff8-b3c6-2020f697e0f9",
            a.id,
        )
        assertEquals("Roger", a.author)
        assertEquals("Die Ausnahme, das Fenster und die Uhr", a.title)
        assertEquals("Ein Zoll, der sich selbst ausnimmt, …", a.summary)
        assertEquals(
            "https://image.nostr.build/a181242b58e9a95dd7474a7d7fbe7a2c77a42a8ff26a841924ec4c8293c7bd54.jpg",
            a.image,
        )
        assertEquals(1_792_226_373L, a.publishedAt)
        assertEquals(
            "https://media.einundzwanzig.space/s/einundzwanzig-news/die-ausnahme-das-fenster-und-die-uhr-71bdfd",
            a.link,
        )
        assertEquals("Ein Zoll, der sich selbst ausnimmt, …", a.content)

        val address = ArticleAddress.parse(a.id)!!
        assertEquals("68240d4932950242924434c5b118035a92448a9570caf1fde7aeca77afbc80e4", address.pubkey)
        assertEquals("draft-1c569370-55a9-4ff8-b3c6-2020f697e0f9", address.d)
    }

    @Test
    fun `cdata title and entity order`() {
        val items = RssParser.parse(
            "<item><title><![CDATA[A &amp; B]]></title><description>x &amp;lt;b&amp;gt; &#39;q&#39; &quot;z&quot;&nbsp;</description></item>",
            now,
        )
        assertEquals("A & B", items[0].title)
        // &amp; is decoded first, so &amp;lt; becomes '<'; the resulting tag is then stripped.
        assertEquals("x 'q' \"z\"", items[0].summary)
    }

    @Test
    fun `dc creator wins over author, content encoded image, link and id fallbacks`() {
        val xml = """
            <ITEM class="x"><dc:creator> Alice </dc:creator><author>Bob</author>
            <guid></guid><link>https://example.org/a</link>
            <description>&lt;p&gt;Hello   <b>world</b>&lt;/p&gt;</description>
            <content:encoded><![CDATA[<p>Body <IMG alt="" SRC="https://img.example/1.png"></p>]]></content:encoded>
            </ITEM>
            <item><title>T2</title><guid>30023:pk:d</guid></item>
        """.trimIndent()
        val items = RssParser.parse(xml, now)
        assertEquals(2, items.size)
        assertEquals("Alice", items[0].author)
        assertEquals(RssParser.UNTITLED, items[0].title)
        assertEquals("https://example.org/a", items[0].id)
        assertEquals("https://img.example/1.png", items[0].image)
        assertEquals("Hello world", items[0].summary)
        assertEquals(now, items[0].publishedAt)
        assertEquals("<p>Body <IMG alt=\"\" SRC=\"https://img.example/1.png\"></p>", items[0].content)
        assertEquals("30023:pk:d", items[1].link)
        assertEquals("", items[1].author)
        assertEquals("", items[1].image)
    }

    @Test
    fun `items without title and description are skipped, tag prefixes do not match`() {
        val xml = "<item><link>https://x</link></item><items><title>no</title></items>" +
            "<item><titles>a</titles><description>d</description></item>"
        val items = RssParser.parse(xml, now)
        assertEquals(1, items.size)
        assertEquals(RssParser.UNTITLED, items[0].title)
        assertEquals("d", items[0].summary)
    }

    @Test
    fun `enclosure url is taken raw and wins over img`() {
        val xml = "<item><title>t</title><description>&lt;img src=\"https://b\"&gt;</description>" +
            "<enclosure type=\"image/png\" url=\"https://a/x.png?a=1&amp;b=2\"/></item>"
        assertEquals("https://a/x.png?a=1&amp;b=2", RssParser.parse(xml, now)[0].image)
    }

    @Test
    fun `stripHtml collapses javascript whitespace but trim is applied by the caller`() {
        assertEquals(" a b ", RssParser.stripHtml("<p>a \n b</p>"))
        assertTrue(RssParser.parse("", now).isEmpty())
    }
}
