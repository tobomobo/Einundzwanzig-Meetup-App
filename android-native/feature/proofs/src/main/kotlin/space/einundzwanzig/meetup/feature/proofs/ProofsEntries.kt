package space.einundzwanzig.meetup.feature.proofs

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.HumanityProof
import space.einundzwanzig.meetup.core.navigation.PlatformProofs
import space.einundzwanzig.meetup.feature.proofs.humanity.HumanityProofRoute
import space.einundzwanzig.meetup.feature.proofs.platform.PlatformProofsRoute

/** Registers the proof screens (spec 06 §2.1, §2.2) in the app's entry provider. */
fun EntryProviderScope<NavKey>.proofsEntries() {
    entry<PlatformProofs> { PlatformProofsRoute() }
    entry<HumanityProof> { HumanityProofRoute() }
}
