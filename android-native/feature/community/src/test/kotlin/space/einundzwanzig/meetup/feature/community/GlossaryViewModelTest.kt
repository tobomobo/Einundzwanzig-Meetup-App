package space.einundzwanzig.meetup.feature.community

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.data.community.glossary.Glossary
import space.einundzwanzig.meetup.data.community.glossary.GlossaryCategory
import space.einundzwanzig.meetup.feature.community.glossary.GlossaryAction
import space.einundzwanzig.meetup.feature.community.glossary.GlossaryViewModel

@RunWith(AndroidJUnit4::class)
class GlossaryViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val entries = Glossary.resolve(context::getString)

    @Test
    fun `blank query lists all categories collapsed`() {
        val vm = GlossaryViewModel(SavedStateHandle())
        val s = vm.state.value
        assertEquals(GlossaryCategory.entries.toList(), s.sections(entries).keys.toList())
        assertEquals(55, s.sections(entries).values.sumOf { it.size })
        assertFalse(s.isExpanded(entries.first().title))
    }

    @Test
    fun `query filters and expands all hits`() {
        val vm = GlossaryViewModel(SavedStateHandle())
        vm.onAction(GlossaryAction.QueryChanged("  NIP-05 "))
        val s = vm.state.value
        val hits = s.sections(entries).values.flatten()
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.title.lowercase().contains("nip-05") || it.body.lowercase().contains("nip-05") })
        assertTrue(s.isExpanded(hits.first().title))
    }

    @Test
    fun `no hits gives empty sections`() {
        val vm = GlossaryViewModel(SavedStateHandle())
        vm.onAction(GlossaryAction.QueryChanged("zzzqqqxx"))
        assertTrue(vm.state.value.sections(entries).isEmpty())
    }

    @Test
    fun `clear resets the query and toggles override the default`() {
        val vm = GlossaryViewModel(SavedStateHandle())
        val title = entries.first().title
        vm.onAction(GlossaryAction.Toggle(title, true))
        assertTrue(vm.state.value.isExpanded(title))
        vm.onAction(GlossaryAction.QueryChanged("badge"))
        vm.onAction(GlossaryAction.Toggle(title, false))
        assertFalse(vm.state.value.isExpanded(title))
        vm.onAction(GlossaryAction.ClearQuery)
        assertEquals("", vm.state.value.query)
    }

    @Test
    fun `query survives process death via SavedStateHandle`() {
        val handle = SavedStateHandle()
        GlossaryViewModel(handle).onAction(GlossaryAction.QueryChanged("relay"))
        assertEquals("relay", GlossaryViewModel(handle).state.value.query)
    }
}
