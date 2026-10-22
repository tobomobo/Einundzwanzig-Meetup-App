package space.einundzwanzig.meetup.feature.meetups.selection

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.feature.meetups.FakeMeetupService
import space.einundzwanzig.meetup.feature.meetups.FakeProfiles
import space.einundzwanzig.meetup.feature.meetups.MainDispatcherRule
import space.einundzwanzig.meetup.feature.meetups.R
import space.einundzwanzig.meetup.feature.meetups.common.UiText
import space.einundzwanzig.meetup.feature.meetups.meetup

class MeetupSelectionViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val list = listOf(
        meetup("107", "Göttingen", "Einundzwanzig Südniedersachsen"),
        meetup("5", "Würzburg", "Würzburg Walk"),
        meetup("6", "Würzburg", "Würzburg Meetup"),
        meetup("8", "Wien", country = "AT"),
    )
    private val meetups = FakeMeetupService(list)
    private val profiles = FakeProfiles(
        UserProfile(nickname = "Hal", favoriteMeetupIds = listOf("107", "Würzburg", "Göttingen", "Wien"), homeMeetupId = "107"),
    )

    private fun vm() = MeetupSelectionViewModel(profiles, meetups)

    @Test
    fun `an unreadable profile blocks saving so the favorites are not wiped`() {
        profiles.failLoad = true
        val vm = vm()
        val s = vm.state.value
        assertFalse(s.loading)
        assertTrue(s.loadFailed)
        assertFalse(s.canSave)
        vm.onAction(MeetupSelectionAction.Save)
        assertEquals(0, profiles.saves)
        profiles.failLoad = false
        vm.onAction(MeetupSelectionAction.Retry)
        assertEquals(listOf("107", "8"), vm.state.value.selected)
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `stored favorites are migrated and preselected`() {
        val s = vm().state.value
        assertFalse(s.loading)
        assertEquals(listOf("107", "8"), s.selected)
        assertTrue(s.canSave)
    }

    @Test
    fun `search filters by city or country, not by group name`() {
        val vm = vm()
        vm.onAction(MeetupSelectionAction.QueryChanged("wÜr"))
        assertEquals(listOf("5", "6"), vm.state.value.visible.map { it.id })
        vm.onAction(MeetupSelectionAction.QueryChanged("at"))
        assertEquals(listOf("8"), vm.state.value.visible.map { it.id })
        vm.onAction(MeetupSelectionAction.QueryChanged("Südnieder"))
        assertTrue(vm.state.value.visible.isEmpty())
    }

    @Test
    fun `toggling keeps insertion order and re-adding moves to the end`() {
        val vm = vm()
        vm.onAction(MeetupSelectionAction.Toggle("5"))
        vm.onAction(MeetupSelectionAction.Toggle("107"))
        vm.onAction(MeetupSelectionAction.Toggle("107"))
        assertEquals(listOf("8", "5", "107"), vm.state.value.selected)
    }

    @Test
    fun `save writes the ids and the first as home, then signals done`() = runTest {
        val vm = vm()
        vm.onAction(MeetupSelectionAction.Toggle("107"))
        vm.onAction(MeetupSelectionAction.Toggle("6"))
        vm.onAction(MeetupSelectionAction.Save)
        vm.saved.first()
        assertEquals(listOf("8", "6"), profiles.current.favoriteMeetupIds)
        assertEquals("8", profiles.current.homeMeetupId)
        assertEquals("Hal", profiles.current.nickname)
    }

    @Test
    fun `saving none clears the home meetup`() = runTest {
        val vm = vm()
        vm.onAction(MeetupSelectionAction.Toggle("107"))
        vm.onAction(MeetupSelectionAction.Toggle("8"))
        vm.onAction(MeetupSelectionAction.Save)
        vm.saved.first()
        assertEquals(emptyList<String>(), profiles.current.favoriteMeetupIds)
        assertEquals("", profiles.current.homeMeetupId)
    }

    @Test
    fun `an empty directory never wipes the favorites`() {
        meetups.list = emptyList()
        val vm = vm()
        val s = vm.state.value
        assertTrue(s.loadFailed)
        assertFalse(s.canSave)
        vm.onAction(MeetupSelectionAction.Save)
        assertEquals(0, profiles.saves)
        meetups.list = list
        vm.onAction(MeetupSelectionAction.Retry)
        assertFalse(vm.state.value.loadFailed)
        assertEquals(listOf("107", "8"), vm.state.value.selected)
    }

    @Test
    fun `a failed save shows an error`() = runTest {
        profiles.failSave = true
        val vm = vm()
        vm.onAction(MeetupSelectionAction.Save)
        assertEquals(UiText.Res(R.string.native_meetups_save_failed), vm.messages.first().text)
        assertFalse(vm.state.value.saving)
    }
}
