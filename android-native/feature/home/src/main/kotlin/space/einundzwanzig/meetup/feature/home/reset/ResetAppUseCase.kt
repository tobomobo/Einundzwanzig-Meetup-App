package space.einundzwanzig.meetup.feature.home.reset

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.data.identity.IdentityRepository
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.issuance.session.RollingQrService
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.mempool.MempoolConfig
import space.einundzwanzig.meetup.data.mempool.MempoolService
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyRepository
import space.einundzwanzig.meetup.data.migration.FlutterMigration
import space.einundzwanzig.meetup.data.profile.NostrProfileService
import space.einundzwanzig.meetup.data.settings.locale.LocaleController
import javax.inject.Inject

/** The full app reset of spec 12 §2.5.12 (`_performReset`). */
fun interface AppResetter {
    /**
     * Wipes everything. `false` if a mandatory step (badges, preferences) failed; the caller then
     * stays on Home (Dart: the exception aborted before the navigation to Intro).
     */
    suspend fun reset(): Boolean
}

/**
 * `_performReset` with the exact order of the Flutter app (spec 12 §2.5.12, §9.3, §14 risk 17):
 * close the NIP-46 session → badges in both storage places → (profile caches, migration state) →
 * all preferences → keys, NIP-46 client key, vault, recovery copies → portal token → in-memory
 * state that would survive the wipe (mempool, currency, locale, tours) and the widget meetup.
 * Runs non-cancellable so leaving the screen never stops it halfway.
 */
class ResetAppUseCase @Inject constructor(
    private val signing: SigningService,
    private val badges: BadgeRepository,
    private val profileService: NostrProfileService,
    private val migration: FlutterMigration,
    private val store: KeyValueStore,
    private val identity: IdentityRepository,
    private val portal: PortalApiService,
    private val rollingQr: RollingQrService,
    private val mempool: MempoolService,
    private val mempoolConfig: MempoolConfig,
    private val currency: CurrencyRepository,
    private val locale: LocaleController,
    private val guide: GuideController,
    private val widget: HomeWidgetStore,
    private val log: DiagnosticLog,
) : AppResetter {

    override suspend fun reset(): Boolean = withContext(NonCancellable) {
        log.info(TAG, "App wird zurückgesetzt.")
        optional("NIP-46 trennen") { signing.disconnectNip46() }
        // Badge data in BOTH places before the preferences (Dart order). Mandatory.
        if (!mandatory("Badges löschen") { badges.resetAll() }) return@withContext false
        // Rolling-QR seeds live in the secure store and survive a prefs wipe (spec 12 §4 residuals).
        optional("Live-Session beenden") { rollingQr.endSession() }
        // Before the prefs wipe so running lookups cannot refill the caches (:data:profile README).
        optional("Profil-Cache leeren") { profileService.clearCache() }
        // :data:migration README: FIRST, then wipe the stores.
        optional("Migrationsstand zurücksetzen") { migration.onAppReset() }
        if (!mandatory("Einstellungen löschen") { check(store.clear()) { "KeyValueStore.clear() fehlgeschlagen" } }) {
            return@withContext false
        }
        optional("Identität löschen") { identity.wipeAll() }
        optional("Portal abmelden") { portal.logout() }
        // In-memory state that a store wipe does not reset (mempool README: after the clear).
        optional("Mempool zurücksetzen") {
            mempoolConfig.resetToDefaults()
            currency.reset()
            mempool.clearState()
        }
        optional("Sprache zurücksetzen") { locale.setLocale(null) }
        optional("Tour beenden") { guide.endTour() }
        optional("Widget leeren") { widget.updateMeetup("", null) }
        optional("Diagnose-Log zurücksetzen") {
            log.setVerbose(false)
            log.clear()
        }
        log.info(TAG, "App zurückgesetzt.")
        true
    }

    private suspend fun optional(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "$name fehlgeschlagen", e)
        }
    }

    private suspend fun mandatory(name: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error(TAG, "$name fehlgeschlagen — Zurücksetzen abgebrochen", e)
        false
    }

    private companion object {
        const val TAG = "Reset"
    }
}
