package space.einundzwanzig.meetup.feature.wallet

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.BadgeCollection
import space.einundzwanzig.meetup.core.navigation.BadgeDetails
import space.einundzwanzig.meetup.core.navigation.BadgeWallet
import space.einundzwanzig.meetup.core.navigation.BadgeWorldMap
import space.einundzwanzig.meetup.core.navigation.WalletTab
import space.einundzwanzig.meetup.feature.wallet.collection.BadgeCollectionRoute
import space.einundzwanzig.meetup.feature.wallet.details.BadgeDetailsRoute
import space.einundzwanzig.meetup.feature.wallet.map.BadgeWorldMapRoute

/** Registers the badge wallet destinations (spec 03 §2.1–§2.4) in the app's `entryProvider`. */
fun EntryProviderScope<NavKey>.walletEntries() {
    entry<WalletTab> { WalletRoute(showBack = false) }
    entry<BadgeWallet> { WalletRoute(showBack = true) }
    entry<BadgeCollection> { key -> BadgeCollectionRoute(key) }
    entry<BadgeDetails> { key -> BadgeDetailsRoute(key) }
    entry<BadgeWorldMap> { BadgeWorldMapRoute() }
}
