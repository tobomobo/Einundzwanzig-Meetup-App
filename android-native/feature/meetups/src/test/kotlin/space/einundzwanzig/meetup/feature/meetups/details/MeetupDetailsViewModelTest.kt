package space.einundzwanzig.meetup.feature.meetups.details

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.feature.meetups.FakeCalendarService
import space.einundzwanzig.meetup.feature.meetups.FakeMeetupService
import space.einundzwanzig.meetup.feature.meetups.FixedTime
import space.einundzwanzig.meetup.feature.meetups.MainDispatcherRule
import space.einundzwanzig.meetup.feature.meetups.meetup
import java.time.Instant

class MeetupDetailsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val time = FixedTime(1_784_000_000_000)
    private val now = Instant.ofEpochMilli(time.now)
    private val meetups = FakeMeetupService(listOf(meetup("107", "Göttingen"), meetup("5", "Augsburg")))

    private fun date(title: String, location: String, hoursFromNow: Long) =
        MeetupDate(title, "", location, now.plusSeconds(hoursFromNow * 3600), "")

    private val calendar = FakeCalendarService(
        listOf(
            date("Stammtisch GÖTTINGEN", "", 48),
            date("Treffen", "Dinx, Göttingen", 2),
            date("Augsburg Meetup", "", 5),
            date("Göttingen alt", "", -7),
            date("Göttingen gerade", "", -5),
        ),
    )

    private fun vm(id: String) = MeetupDetailsViewModel(id, meetups, calendar, time)

    @Test
    fun `resolves from the portal list and filters dates by city, sorted, from now minus 6 h`() {
        val s = vm("107").state.value
        assertFalse(s.resolving)
        assertEquals("Göttingen", s.meetup?.city)
        assertFalse(s.datesLoading)
        assertEquals(listOf("Göttingen gerade", "Treffen", "Stammtisch GÖTTINGEN"), s.dates.map { it.title })
    }

    @Test
    fun `uses the cache before fetching`() {
        meetups.cache = listOf(meetup("107", "Göttingen"))
        vm("107")
        assertEquals(0, meetups.fetchCalls)
    }

    @Test
    fun `falls back to the bundled list`() {
        meetups.list = emptyList()
        val s = vm("m_muc").state.value
        assertEquals("München", s.meetup?.city)
    }

    @Test
    fun `unknown or empty id shows not found`() {
        assertTrue(vm("nope").state.value.notFound)
        assertTrue(vm("").state.value.notFound)
    }

    @Test
    fun `opening and closing a date sheet`() {
        val vm = vm("107")
        vm.onAction(MeetupDetailsAction.OpenDate(1))
        assertEquals(1, vm.state.value.openDate)
        vm.onAction(MeetupDetailsAction.CloseDate)
        assertNull(vm.state.value.openDate)
    }
}
