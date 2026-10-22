package space.einundzwanzig.meetup.feature.reputation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.ReputationCard
import space.einundzwanzig.meetup.core.navigation.ReputationQr
import space.einundzwanzig.meetup.core.navigation.ReputationScanResult
import space.einundzwanzig.meetup.core.navigation.ReputationScanner
import space.einundzwanzig.meetup.core.navigation.ReputationVerify
import space.einundzwanzig.meetup.core.navigation.TrustNetworkHub
import space.einundzwanzig.meetup.core.navigation.VerifyPerson
import space.einundzwanzig.meetup.core.navigation.VerifyPersonQrCamera
import space.einundzwanzig.meetup.feature.reputation.card.ReputationCardRoute
import space.einundzwanzig.meetup.feature.reputation.hub.TrustNetworkHubRoute
import space.einundzwanzig.meetup.feature.reputation.person.VerifyPersonQrCameraRoute
import space.einundzwanzig.meetup.feature.reputation.person.VerifyPersonRoute
import space.einundzwanzig.meetup.feature.reputation.qr.ReputationQrRoute
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScanResultRoute
import space.einundzwanzig.meetup.feature.reputation.scan.ReputationScannerRoute
import space.einundzwanzig.meetup.feature.reputation.verify.ReputationVerifyRoute

/** Registers the trust & reputation screens (spec 04 §2) in the app's entry provider. */
fun EntryProviderScope<NavKey>.reputationEntries() {
    entry<ReputationQr> { ReputationQrRoute() }
    entry<ReputationCard> { ReputationCardRoute() }
    entry<ReputationScanner> { ReputationScannerRoute() }
    entry<ReputationScanResult> { key -> ReputationScanResultRoute(key.code) }
    entry<ReputationVerify> { key -> ReputationVerifyRoute(key.initialInput) }
    entry<VerifyPerson> { key -> VerifyPersonRoute(key.initialTargetNpub) }
    entry<VerifyPersonQrCamera> { VerifyPersonQrCameraRoute() }
    entry<TrustNetworkHub> { TrustNetworkHubRoute() }
}
