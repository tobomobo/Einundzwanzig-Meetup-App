package space.einundzwanzig.meetup.core.ui.guide

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.util.UiLog

/**
 * The spotlight tours. [persistName] is the Dart enum name used in the persisted key
 * `guide_completed_<persistName>` — never derive it from the Kotlin name (`myMeetups`,
 * `portalArea` are camelCase in Dart, spec 12 §14.2).
 */
enum class GuideTour(val persistName: String) {
    Onboarding("onboarding"),
    Home("home"),
    Settings("settings"),
    Events("events"),

    /** Community area (CommunityTour). */
    Portal("portal"),

    /** Badge wallet. */
    Wallet("wallet"),

    /** Trust network (never started, persisted by "decline all"). */
    Reputation("reputation"),
    Scan("scan"),

    /** Portal dates of the own meetup. */
    MyMeetups("myMeetups"),

    /** Portal area: meetups, events, courses, map. */
    PortalArea("portalArea"),
}

/**
 * One step of a spotlight tour (port of Dart `GuideStep`, spec 12 §3.3). Tour definitions
 * live in the feature modules; targets are referenced by the id given to
 * [androidx.compose.ui.Modifier.guideAnchor].
 *
 * @property targetId anchor to spotlight; null = centered explanation dialog.
 * @property title tooltip title.
 * @property body tooltip text.
 * @property hint optional call to action shown in the accent colour ("Tippe ins Feld …").
 * @property blockOutside blocks every touch outside the hole (vertical drags still scroll the
 *   target's scroll container).
 * @property interactiveTarget whether the hole itself can be used; false = show only.
 * @property advanceOnTargetTap a tap on the target advances one step (targets that open
 *   something, e.g. a sheet).
 * @property completeWhen condition that unlocks "Weiter"; evaluated every 40 ms, keep it cheap.
 *   null = pure explanation, always complete.
 * @property autoAdvance advance automatically 700 ms after [completeWhen] is met.
 * @property waitTimeoutMillis a target that stays missing (and not covered) this long is
 *   skipped instead of hanging the tour.
 * @property holePadding space between target and hole edge.
 * @property holeRadius corner radius of the hole.
 * @property scrollIntoView scroll the target into view (with 24 dp margins) when it appears.
 */
@Immutable
data class GuideStep(
    val targetId: String? = null,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    @param:StringRes val hint: Int? = null,
    val blockOutside: Boolean = true,
    val interactiveTarget: Boolean = true,
    val advanceOnTargetTap: Boolean = false,
    val completeWhen: (() -> Boolean)? = null,
    val autoAdvance: Boolean = false,
    val waitTimeoutMillis: Long = DEFAULT_WAIT_TIMEOUT_MS,
    val holePadding: Dp = 10.dp,
    val holeRadius: Dp = 14.dp,
    val scrollIntoView: Boolean = true,
) {
    /** Does this step expect an action from the user? */
    val requiresAction: Boolean get() = completeWhen != null || advanceOnTargetTap

    /**
     * Whether the expected action is done; always true without [completeWhen]. A throwing
     * condition counts as done: better let the tour continue than block it while some state is
     * briefly unreachable during a navigation.
     */
    val isComplete: Boolean
        get() {
            val check = completeWhen ?: return true
            return try {
                check()
            } catch (e: RuntimeException) {
                UiLog.warn("Guide", "completeWhen fehlgeschlagen, Schritt gilt als erledigt", e)
                true
            }
        }

    companion object {
        /** Default wait for a missing target (Flutter: 6 s). */
        const val DEFAULT_WAIT_TIMEOUT_MS: Long = 6_000
    }
}

/**
 * Persistence of tour progress, implemented in a data module on top of the settings
 * DataStore with the Flutter keys [completedKey] and [ONBOARDING_ASKED_KEY].
 */
interface TourProgressStore {
    /** `guide_completed_<tour>` is true. */
    suspend fun isCompleted(tour: GuideTour): Boolean

    /** Writes `true`, or removes the key for `false` (Flutter `prefs.remove`). */
    suspend fun setCompleted(tour: GuideTour, completed: Boolean)

    /** `guide_onboarding_asked` is true. */
    suspend fun isOnboardingAsked(): Boolean

    /** Writes `true`, or removes the key for `false`. */
    suspend fun setOnboardingAsked(asked: Boolean)

    companion object {
        /** Preference key of the "first-run tour question was asked" flag. */
        const val ONBOARDING_ASKED_KEY = "guide_onboarding_asked"

        /** Preference key marking [tour] as completed (`guide_completed_<persistName>`). */
        fun completedKey(tour: GuideTour): String = "guide_completed_${tour.persistName}"
    }
}

/** Volatile [TourProgressStore] for previews and tests; keys as in [TourProgressStore]. */
class InMemoryTourProgressStore : TourProgressStore {
    /** Stored flags by preference key (only `true` values are present). */
    val values: MutableMap<String, Boolean> = linkedMapOf()

    override suspend fun isCompleted(tour: GuideTour): Boolean = values[TourProgressStore.completedKey(tour)] == true

    override suspend fun setCompleted(tour: GuideTour, completed: Boolean) {
        val key = TourProgressStore.completedKey(tour)
        if (completed) values[key] = true else values.remove(key)
    }

    override suspend fun isOnboardingAsked(): Boolean = values[TourProgressStore.ONBOARDING_ASKED_KEY] == true

    override suspend fun setOnboardingAsked(asked: Boolean) {
        if (asked) values[TourProgressStore.ONBOARDING_ASKED_KEY] = true else values.remove(TourProgressStore.ONBOARDING_ASKED_KEY)
    }
}
