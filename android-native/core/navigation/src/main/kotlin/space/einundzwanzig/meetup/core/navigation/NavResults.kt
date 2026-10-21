package space.einundzwanzig.meetup.core.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import androidx.navigation3.runtime.result.ResultEventBus

/**
 * Typed name of a value that a destination hands back to the screen below it, delivered through
 * Navigation 3's [ResultEventBus].
 *
 * The producer calls [ResultEventBus.sendResult] and then [Navigator.back]; the consumer collects
 * with [NavResultEffect]. The bus buffers the value until the consumer is composed again, and
 * popping without sending means "cancelled". `:app` provides the bus with
 * `CompositionLocalProvider(LocalResultEventBus provides rememberResultEventBus())` around its
 * `NavDisplay`.
 *
 * Two keys are equal when their [name]s are equal. The constructor is internal so that every key is
 * declared once in [NavResults]; a feature cannot create a second key with the same name and a
 * different payload type (Navigation 3's `ResultEffect` casts unchecked).
 *
 * @param T payload type.
 * @property name the bus channel name.
 */
class NavResultKey<T : Any> internal constructor(val name: String) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is NavResultKey<*> && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = "NavResultKey($name)"
}

/**
 * A point picked on the map by [LocationPicker].
 *
 * @property latitude WGS84 latitude in degrees.
 * @property longitude WGS84 longitude in degrees.
 */
data class PickedLocation(
    val latitude: Double,
    val longitude: Double,
)

/** Result keys of the destinations that return a value to their caller. */
object NavResults {
    /** [LocationPicker] → event editor: the confirmed venue point ("Ort übernehmen", spec 09 §2.6). */
    val LocationPicked: NavResultKey<PickedLocation> = NavResultKey("einundzwanzig.result.LocationPicked")

    /**
     * [BadgeQrCamera] → [BadgeScanner]: raw text of the accepted QR code, either the app-review
     * payload or a JSON object with `t`/`type` (spec 02 §2.2).
     */
    val BadgeQrScanned: NavResultKey<String> = NavResultKey("einundzwanzig.result.BadgeQrScanned")

    /** [VerifyPersonQrCamera] → [VerifyPerson]: the scanned npub, lower-cased (spec 04 §2.7.1). */
    val NpubScanned: NavResultKey<String> = NavResultKey("einundzwanzig.result.NpubScanned")

    /**
     * [EventEditor] → [EventCalendar]: the event was published (Flutter `pop(true)`, spec 10 §2.4);
     * the calendar reloads.
     */
    val EventPublished: NavResultKey<Unit> = NavResultKey("einundzwanzig.result.EventPublished")

    /**
     * [PortalEventEditor] → [PortalMeetupEvents]: the date was saved (Flutter `pop(true)`,
     * spec 09 §2.1). Payload `true` for a new date ("Termin angelegt ✓"), `false` for an edited one
     * ("Termin aktualisiert ✓").
     */
    val PortalEventSaved: NavResultKey<Boolean> = NavResultKey("einundzwanzig.result.PortalEventSaved")

    /** Every key above, for uniqueness checks. */
    val all: List<NavResultKey<*>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        listOf(LocationPicked, BadgeQrScanned, NpubScanned, EventPublished, PortalEventSaved)
    }
}

/** Sends [result] for [key]; call [Navigator.back] afterwards. */
fun <T : Any> ResultEventBus.sendResult(key: NavResultKey<T>, result: T) {
    sendResult(key.name, result)
}

/**
 * Collects the results sent for [key] while this composable is in the composition and calls
 * [onResult] for each of them.
 */
@Composable
fun <T : Any> NavResultEffect(
    key: NavResultKey<T>,
    resultEventBus: ResultEventBus = LocalResultEventBus.current,
    onResult: suspend (T) -> Unit,
) {
    ResultEffect(key.name, resultEventBus, onResult)
}
