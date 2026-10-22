package space.einundzwanzig.meetup.feature.bitcoin

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.BitcoinDashboard
import space.einundzwanzig.meetup.core.navigation.Converter
import space.einundzwanzig.meetup.core.navigation.MempoolSettings
import space.einundzwanzig.meetup.feature.bitcoin.converter.ConverterRoute
import space.einundzwanzig.meetup.feature.bitcoin.dashboard.BitcoinDashboardRoute
import space.einundzwanzig.meetup.feature.bitcoin.settings.MempoolSettingsRoute

/** Registers the Bitcoin tool destinations (spec 13 §2.1–§2.3). */
fun EntryProviderScope<NavKey>.bitcoinEntries() {
    entry<BitcoinDashboard> { BitcoinDashboardRoute() }
    entry<Converter> { ConverterRoute() }
    entry<MempoolSettings> { MempoolSettingsRoute() }
}
