package space.einundzwanzig.meetup.core.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey

/**
 * App-wide navigation contract. Feature modules never know about each other; they only push
 * [NavKey]s declared in this module (see Destinations.kt) and the :app module maps keys to screens.
 *
 * Mapping of the Flutter navigator calls used in the specs:
 * `push` → [navigate], `pushReplacement` → [replaceTop], `pop` → [back],
 * `pushAndRemoveUntil(..., (_) => false)` → [resetTo],
 * `pushAndRemoveUntil(..., (route) => route.isFirst)` → [navigateFromRoot].
 * Values a screen returns go through [NavResultKey].
 */
interface Navigator {
    /** Pushes [key] on top of the current back stack. */
    fun navigate(key: NavKey)

    /** Replaces the top entry with [key] (Flutter `pushReplacement`). */
    fun replaceTop(key: NavKey)

    /** Pops the top entry; does nothing when it is the last one. */
    fun back()

    /**
     * Pops entries until [key] is on top. With [inclusive] the entry for [key] is popped as well.
     * Does nothing when [key] is not on the back stack.
     */
    fun backTo(key: NavKey, inclusive: Boolean = false)

    /** Clears the back stack and makes [key] the new root (e.g. after onboarding). */
    fun resetTo(key: NavKey)

    /**
     * Pops every entry above the current root of the active back stack, then pushes [key]
     * (Flutter `pushAndRemoveUntil(route, (route) => route.isFirst)`). The root stays, so back from
     * [key] returns to it; e.g. `NfcTagWriter` and `MeetupSessionWizard` open [RollingQr] this way.
     */
    fun navigateFromRoot(key: NavKey)

    /** Opens an http(s) [url] externally; other schemes are ignored (spec 12 §2.10 `launchHttpUrl`). */
    fun openUrl(url: String)
}

/** The [Navigator] of the current composition; `:app` provides it above its `NavDisplay`. */
val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator provided") }
