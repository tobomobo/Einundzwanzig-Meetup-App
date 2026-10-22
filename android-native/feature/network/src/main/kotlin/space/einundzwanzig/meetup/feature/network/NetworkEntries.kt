package space.einundzwanzig.meetup.feature.network

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.MyTrustNetwork

/** Registers the destinations of `:feature:network` (see core/navigation/ROUTES.md). */
fun EntryProviderScope<NavKey>.networkEntries() {
    entry<MyTrustNetwork> { MyNetworkRoute() }
}
