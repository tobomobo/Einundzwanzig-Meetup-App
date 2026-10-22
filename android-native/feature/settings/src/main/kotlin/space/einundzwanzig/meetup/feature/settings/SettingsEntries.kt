package space.einundzwanzig.meetup.feature.settings

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.DiagnosticLog
import space.einundzwanzig.meetup.feature.settings.log.DiagnosticLogRoute

/**
 * Registers the settings destinations: only [DiagnosticLog] (spec 12 §2.8). The settings sheet
 * itself is not a destination; `:feature:home` hosts [space.einundzwanzig.meetup.feature.settings.sheet.SettingsSheet].
 */
fun EntryProviderScope<NavKey>.settingsEntries() {
    entry<DiagnosticLog> { DiagnosticLogRoute() }
}
