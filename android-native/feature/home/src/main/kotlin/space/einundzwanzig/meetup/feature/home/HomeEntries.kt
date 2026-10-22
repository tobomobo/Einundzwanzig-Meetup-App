package space.einundzwanzig.meetup.feature.home

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.HomeTab
import space.einundzwanzig.meetup.feature.home.ui.HomeRoute

/** Registers the Home dashboard tab (spec 12 §2.5) in the app's `entryProvider`. */
fun EntryProviderScope<NavKey>.homeEntries() {
    entry<HomeTab> { HomeRoute() }
}
