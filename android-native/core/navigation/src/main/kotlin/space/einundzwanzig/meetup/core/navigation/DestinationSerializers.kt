package space.einundzwanzig.meetup.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * Registers every [Destination] as a polymorphic subclass of [NavKey].
 *
 * On Android `rememberNavBackStack(vararg keys)` saves keys by class name and needs no module. Use
 * this module where keys are encoded polymorphically as [NavKey], e.g.
 * `rememberNavBackStack(SavedStateConfiguration { serializersModule = DestinationSerializersModule }, ...)`
 * or `Json { serializersModule = DestinationSerializersModule }`.
 */
val DestinationSerializersModule: SerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclassesOfSealed<Destination>()
    }
}
