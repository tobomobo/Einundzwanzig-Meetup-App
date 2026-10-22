package space.einundzwanzig.meetup.feature.wallet.crest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.core.ui.crest.LocalMeetupCrestSource
import space.einundzwanzig.meetup.core.ui.crest.MeetupCrestSource
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MeetupCrestSource] on top of the portal logo registry of `:data:meetups` (spec 03 §2.5 URL
 * resolution): an own image URL is trimmed and absolutized; otherwise the city (text before the
 * first comma) is looked up with `logoFor`, re-evaluated whenever the registry changes.
 */
@Singleton
internal class WalletCrestSource @Inject constructor(
    private val registry: PortalLogoRegistry,
    @param:ApplicationScope private val scope: CoroutineScope,
) : MeetupCrestSource {

    override fun crestUrl(meetupName: String, imageUrl: String?): Flow<String?> {
        val own = imageUrl?.dartTrim().orEmpty()
        if (own.isNotEmpty()) return flowOf(registry.absoluteImageUrl(own))
        val city = cityOf(meetupName)
        if (city.isEmpty()) return flowOf(null)
        return registry.logos
            .map { registry.logoFor(city).ifEmpty { null } }
            .distinctUntilChanged()
    }

    override fun ensureLoaded() {
        scope.launch {
            runSuspendCatching { registry.ensureLoaded() }
                .onFailure { AppLogger.warn(TAG, "Wappen-Logos nicht geladen", it) }
        }
    }

    companion object {
        private const val TAG = "Wallet"

        /** `meetupName.split(',').first().trim()` (spec 03 §2.5). */
        fun cityOf(meetupName: String): String = meetupName.split(',').first().dartTrim()
    }
}

/**
 * Provides the crest source to [content] unless the app shell already provides one
 * (`LocalMeetupCrestSource`, core:ui README). Reads the singleton binding of [WalletCrestModule]
 * through a Hilt entry point, so no per-entry holder ViewModel is created.
 */
@Composable
internal fun ProvideWalletCrests(content: @Composable () -> Unit) {
    if (LocalMeetupCrestSource.current !== MeetupCrestSource.None) {
        content()
        return
    }
    val context = LocalContext.current
    val source = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, WalletCrestEntryPoint::class.java).meetupCrestSource()
    }
    CompositionLocalProvider(LocalMeetupCrestSource provides source, content = content)
}
