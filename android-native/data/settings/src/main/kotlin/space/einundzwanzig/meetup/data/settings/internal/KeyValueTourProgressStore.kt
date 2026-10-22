package space.einundzwanzig.meetup.data.settings.internal

import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.TourProgressStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TourProgressStore] on the shared [KeyValueStore] with the Flutter keys
 * `guide_completed_<persistName>` and `guide_onboarding_asked` (Dart `GuideService`).
 *
 * Dart writes `true` and removes the key on reset (`prefs.remove`), so `false` is never stored.
 */
@Singleton
internal class KeyValueTourProgressStore @Inject constructor(
    private val store: KeyValueStore,
) : TourProgressStore {

    override suspend fun isCompleted(tour: GuideTour): Boolean =
        store.getBool(TourProgressStore.completedKey(tour)) ?: false

    override suspend fun setCompleted(tour: GuideTour, completed: Boolean) {
        val key = TourProgressStore.completedKey(tour)
        val ok = if (completed) store.setBool(key, true) else store.remove(key)
        if (!ok) AppLogger.warn(TAG, "Tour-Fortschritt ${tour.persistName} konnte nicht gespeichert werden")
    }

    override suspend fun isOnboardingAsked(): Boolean =
        store.getBool(TourProgressStore.ONBOARDING_ASKED_KEY) ?: false

    override suspend fun setOnboardingAsked(asked: Boolean) {
        val key = TourProgressStore.ONBOARDING_ASKED_KEY
        val ok = if (asked) store.setBool(key, true) else store.remove(key)
        if (!ok) AppLogger.warn(TAG, "Tour-Abfrage konnte nicht gespeichert werden")
    }

    private companion object {
        const val TAG = "Guide"
    }
}
