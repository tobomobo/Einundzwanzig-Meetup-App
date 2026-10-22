package space.einundzwanzig.meetup.feature.badges

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.BadgeQrCamera
import space.einundzwanzig.meetup.core.navigation.BadgeScanner
import space.einundzwanzig.meetup.core.navigation.MeetupSessionWizard
import space.einundzwanzig.meetup.core.navigation.NfcTagWriter
import space.einundzwanzig.meetup.core.navigation.RollingQr
import space.einundzwanzig.meetup.feature.badges.camera.BadgeQrCameraRoute
import space.einundzwanzig.meetup.feature.badges.rollingqr.RollingQrRoute
import space.einundzwanzig.meetup.feature.badges.scanner.BadgeScannerRoute
import space.einundzwanzig.meetup.feature.badges.wizard.MeetupSessionWizardRoute
import space.einundzwanzig.meetup.feature.badges.writer.NfcTagWriterRoute

/** Registers the destinations of `:feature:badges` (spec 02 §2.1–§2.5, see core/navigation/ROUTES.md). */
fun EntryProviderScope<NavKey>.badgesEntries() {
    entry<BadgeScanner> { BadgeScannerRoute() }
    entry<BadgeQrCamera> { BadgeQrCameraRoute() }
    entry<RollingQr> { RollingQrRoute() }
    entry<NfcTagWriter> { NfcTagWriterRoute() }
    entry<MeetupSessionWizard> { MeetupSessionWizardRoute() }
}
