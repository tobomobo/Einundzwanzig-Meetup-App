package space.einundzwanzig.meetup.feature.profile

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.ProfileEdit
import space.einundzwanzig.meetup.core.navigation.RelaySettings
import space.einundzwanzig.meetup.feature.profile.edit.ProfileEditRoute
import space.einundzwanzig.meetup.feature.profile.relay.RelaySettingsRoute

/** Registers the profile screens (spec 07 §2.1, §2.2) in the app's entry provider. */
fun EntryProviderScope<NavKey>.profileEntries() {
    entry<ProfileEdit> { ProfileEditRoute() }
    entry<RelaySettings> { RelaySettingsRoute() }
}
