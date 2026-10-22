package space.einundzwanzig.meetup.feature.events

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.EventCalendar
import space.einundzwanzig.meetup.core.navigation.EventEditor
import space.einundzwanzig.meetup.core.navigation.EventsTab
import space.einundzwanzig.meetup.core.navigation.MeetupCalendar
import space.einundzwanzig.meetup.core.navigation.MyEvents
import space.einundzwanzig.meetup.feature.events.calendar.EventCalendarRoute
import space.einundzwanzig.meetup.feature.events.editor.EventEditorRoute
import space.einundzwanzig.meetup.feature.events.hub.EventsHubRoute
import space.einundzwanzig.meetup.feature.events.meetups.MeetupCalendarRoute
import space.einundzwanzig.meetup.feature.events.myevents.MyEventsRoute

/** Registers the calendar & events destinations (spec 10 §2.1–§2.5) in the app's `entryProvider`. */
fun EntryProviderScope<NavKey>.eventsEntries() {
    entry<EventsTab> { EventsHubRoute() }
    entry<MeetupCalendar> { key -> MeetupCalendarRoute(key) }
    entry<EventCalendar> { key -> EventCalendarRoute(key) }
    entry<EventEditor> { EventEditorRoute() }
    entry<MyEvents> { MyEventsRoute() }
}
