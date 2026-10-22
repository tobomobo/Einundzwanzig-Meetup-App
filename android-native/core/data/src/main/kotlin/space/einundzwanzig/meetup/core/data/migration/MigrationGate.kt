package space.einundzwanzig.meetup.core.data.migration

import javax.inject.Qualifier

/** Process barrier shared by every persistence entry point, including workers and widgets. */
interface MigrationGate {
    val isComplete: Boolean
    suspend fun await()
}

/** Legacy key names that must remain unreadable until imported or explicitly abandoned. */
fun interface LegacySecretStatus {
    suspend fun pendingLegacySecrets(): Set<String>
}

/** Only the migration may use these stores: awaiting its own gate would deadlock. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Ungated
