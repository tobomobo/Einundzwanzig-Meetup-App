package space.einundzwanzig.meetup.data.migration.internal

import space.einundzwanzig.meetup.core.common.log.AppLogger

/** Seam for `AppLogger.importLegacy` (contradiction 3: the Flutter log never goes into `KeyValueStore`). */
internal fun interface LegacyLogImporter {
    suspend fun import(rawDartJson: String?, verbose: Boolean?)

    /**
     * Registers the imported secret values with the log redactor **before** [import], so a value
     * a Flutter build may have logged (64-hex keys, seeds, tokens have no pattern rule) is
     * redacted on the way into `diagnostic_log.jsonl`. No-op for test doubles that do not care.
     */
    fun registerSecrets(values: Collection<String>) = Unit
}

/** Production importer: the process-wide diagnostic logger redacts and merges the Flutter entries. */
internal object AppLoggerLegacyImporter : LegacyLogImporter {
    override suspend fun import(rawDartJson: String?, verbose: Boolean?) = AppLogger.importLegacy(rawDartJson, verbose)

    override fun registerSecrets(values: Collection<String>) = values.forEach(AppLogger::registerSecret)
}
