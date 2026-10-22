package space.einundzwanzig.meetup.feature.meetups

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.CommunityPortal
import space.einundzwanzig.meetup.core.navigation.LocationPicker
import space.einundzwanzig.meetup.core.navigation.MeetupDetails
import space.einundzwanzig.meetup.core.navigation.MeetupSelection
import space.einundzwanzig.meetup.core.navigation.NearbyMeetups
import space.einundzwanzig.meetup.core.navigation.PortalEventEditor
import space.einundzwanzig.meetup.core.navigation.PortalMeetupEvents
import space.einundzwanzig.meetup.core.navigation.PortalMeetups
import space.einundzwanzig.meetup.feature.meetups.details.MeetupDetailsRoute
import space.einundzwanzig.meetup.feature.meetups.nearby.NearbyMeetupsRoute
import space.einundzwanzig.meetup.feature.meetups.picker.LocationPickerRoute
import space.einundzwanzig.meetup.feature.meetups.portal.PortalEventEditorRoute
import space.einundzwanzig.meetup.feature.meetups.portal.PortalMeetupEventsRoute
import space.einundzwanzig.meetup.feature.meetups.portal.PortalMeetupsRoute
import space.einundzwanzig.meetup.feature.meetups.selection.MeetupSelectionRoute
import space.einundzwanzig.meetup.feature.meetups.web.CommunityPortalRoute

/** Registers the meetup, location and portal destinations (spec 09 §2.1–§2.7). */
fun EntryProviderScope<NavKey>.meetupsEntries() {
    entry<NearbyMeetups> { NearbyMeetupsRoute() }
    entry<MeetupDetails> { key -> MeetupDetailsRoute(key.meetupId) }
    entry<MeetupSelection> { MeetupSelectionRoute() }
    entry<LocationPicker> { key -> LocationPickerRoute(key.initialLatitude, key.initialLongitude) }
    entry<PortalMeetups> { PortalMeetupsRoute() }
    entry<PortalMeetupEvents> { key -> PortalMeetupEventsRoute(key.meetupId, key.meetupName) }
    entry<PortalEventEditor> { key -> PortalEventEditorRoute(key) }
    entry<CommunityPortal> { CommunityPortalRoute() }
}
