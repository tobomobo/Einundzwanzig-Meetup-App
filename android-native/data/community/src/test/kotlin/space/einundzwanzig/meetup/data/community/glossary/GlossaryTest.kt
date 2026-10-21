package space.einundzwanzig.meetup.data.community.glossary

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class GlossaryTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun has55EntriesInFlutterOrder() {
        assertEquals(55, Glossary.entries.size)
        val counts = GlossaryCategory.entries.map { c -> Glossary.entries.count { it.category == c } }
        assertEquals(listOf(6, 8, 7, 7, 10, 6, 7, 4), counts)
        // Categories appear as contiguous blocks in enum order.
        assertEquals(Glossary.entries.map { it.category }, Glossary.entries.map { it.category }.sortedBy { it.ordinal })
        assertEquals(L10nR.string.glWhatIsAppTitle, Glossary.entries.first().titleRes)
        assertEquals(L10nR.string.glNip05Body, Glossary.entries.first { it.titleRes == L10nR.string.glNip05Title }.bodyRes)
        assertEquals(L10nR.string.glResetBody, Glossary.entries.last().bodyRes)
        val ids = Glossary.entries.flatMap { listOf(it.titleRes, it.bodyRes) }
        assertEquals(110, ids.toSet().size)
    }

    @Test
    @Config(qualifiers = "de")
    fun allStringsResolve() {
        val resolved = Glossary.resolve(app::getString)
        assertTrue(resolved.all { it.title.isNotBlank() && it.body.isNotBlank() })
        assertTrue(GlossaryCategory.entries.all { app.getString(it.labelRes).isNotBlank() })
        assertEquals("Was diese App tut", resolved.first().title)
    }

    private val sample = listOf(
        entry(GlossaryCategory.Start, "Was diese App tut", "Sammelt Badges."),
        entry(GlossaryCategory.Badges, "Rolling QR", "Wechselt alle paar Sekunden."),
        entry(GlossaryCategory.Nostr, "Relays", "Server, die NOSTR-Events speichern."),
        entry(GlossaryCategory.Nostr, "Zap", "Lightning-Zahlung."),
    )

    private fun entry(c: GlossaryCategory, title: String, body: String) =
        ResolvedGlossaryEntry(GlossaryEntry(c, 0, 0), title, body)

    @Test
    fun blankQueryReturnsAll() {
        assertEquals(sample, Glossary.search(sample, ""))
        assertEquals(sample, Glossary.search(sample, "   "))
    }

    @Test
    fun searchMatchesTitleOrBodyCaseInsensitive() {
        assertEquals(listOf("Relays"), Glossary.search(sample, "nostr").map { it.title })
        assertEquals(listOf("Rolling QR"), Glossary.search(sample, "  rolling q ").map { it.title })
        assertEquals(listOf("Zap"), Glossary.search(sample, "LIGHTNING").map { it.title })
        assertEquals(emptyList<ResolvedGlossaryEntry>(), Glossary.search(sample, "xyz"))
    }

    @Test
    fun groupsHitsByCategoryInEnumOrder() {
        val grouped = Glossary.groupByCategory(sample.reversed())
        assertEquals(listOf(GlossaryCategory.Start, GlossaryCategory.Badges, GlossaryCategory.Nostr), grouped.keys.toList())
        assertEquals(listOf("Zap", "Relays"), grouped.getValue(GlossaryCategory.Nostr).map { it.title })
    }
}
