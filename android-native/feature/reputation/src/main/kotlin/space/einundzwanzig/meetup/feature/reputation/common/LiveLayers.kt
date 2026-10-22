package space.einundzwanzig.meetup.feature.reputation.common

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Result
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.socialgraph.social.SocialAnalysis
import space.einundzwanzig.meetup.feature.reputation.data.ReputationDataSource

/** One of the three live loaders of the scanner result and the verify screen (spec 04 §2.3, §2.4). */
internal enum class LayerLoad { SOCIAL, LIGHTNING, NIP05 }

/**
 * Live NIP-05 / Lightning / social data of another person, loaded in parallel. Errors are
 * swallowed per layer (the result stays `null`). [pending] replaces Flutter's "last writer wins"
 * status text: the screen shows the status of the first still running loader.
 */
@Immutable
internal data class LiveLayers(
    val pending: Set<LayerLoad> = emptySet(),
    val social: SocialAnalysis? = null,
    val zaps: ZapStats? = null,
    val nip05: Nip05Result? = null,
) {
    val isLoading: Boolean get() = pending.isNotEmpty()

    /** The loader whose status text to show while [isLoading]. */
    val currentLoad: LayerLoad? get() = LayerLoad.entries.firstOrNull { it in pending }
}

/**
 * Loads the three layers for [pubkeyHex] in parallel and reports every change through
 * [update]. Returns when all three finished.
 */
internal suspend fun loadLiveLayers(
    source: ReputationDataSource,
    pubkeyHex: String,
    update: ((LiveLayers) -> LiveLayers) -> Unit,
) = coroutineScope {
    update { it.copy(pending = LayerLoad.entries.toSet()) }
    launch {
        val social = runSuspendCatching { source.analyzeSocial(pubkeyHex) }
            .onFailure { AppLogger.warn(TAG, "Soziale Analyse fehlgeschlagen", it) }
            .getOrNull()
        update { it.copy(social = social, pending = it.pending - LayerLoad.SOCIAL) }
    }
    launch {
        val zaps = runSuspendCatching { source.analyzeZaps(pubkeyHex) }
            .onFailure { AppLogger.warn(TAG, "Zap-Analyse fehlgeschlagen", it) }
            .getOrNull()
        update { it.copy(zaps = zaps, pending = it.pending - LayerLoad.LIGHTNING) }
    }
    launch {
        val nip05 = runSuspendCatching { source.checkNip05(pubkeyHex) }
            .onFailure { AppLogger.warn(TAG, "NIP-05-Prüfung fehlgeschlagen", it) }
            .getOrNull()
        update { it.copy(nip05 = nip05, pending = it.pending - LayerLoad.NIP05) }
    }
}

private const val TAG = "ReputationLayers"
