package space.einundzwanzig.meetup.data.calendar.internal

import dagger.Lazy
import space.einundzwanzig.meetup.data.calendar.MeetupCalendarService
import space.einundzwanzig.meetup.data.meetups.nearby.MeetupEventInfo
import space.einundzwanzig.meetup.data.meetups.nearby.MeetupEventsSource
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoLoader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `:data:meetups` hooks: nearby dates and crest loading, both via `fetchPortalFirst()`.
 *
 * [dates] is [Lazy] because `PortalLogoRegistry` → `PortalLogoLoader` (this) →
 * `MeetupCalendarService` → `PortalLogoRegistry` is a dependency cycle.
 */
@Singleton
internal class CalendarHooks @Inject constructor(
    private val dates: Lazy<MeetupCalendarService>,
) : MeetupEventsSource, PortalLogoLoader {

    override suspend fun fetchMeetupEvents(): List<MeetupEventInfo> = dates.get().fetchPortalFirst().map { it.toEventInfo() }

    override suspend fun loadLogos() {
        dates.get().fetchPortalFirst()
    }
}
