package space.einundzwanzig.meetup.feature.admin

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.AdminPanel

/** Registers the destinations of `:feature:admin` (see core/navigation/ROUTES.md). */
fun EntryProviderScope<NavKey>.adminEntries() {
    entry<AdminPanel> { AdminPanelRoute() }
}
