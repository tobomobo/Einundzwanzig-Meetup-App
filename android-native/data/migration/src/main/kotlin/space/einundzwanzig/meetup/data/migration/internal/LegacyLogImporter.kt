package space.einundzwanzig.meetup.data.migration.internal

import space.einundzwanzig.meetup.core.common.log.AppLogger

/** Seam for `AppLogger.importLegacy` (contradiction 3: the Flutter log never goes into `KeyValueStore`). */
internal fun interface LegacyLogImporter {
    suspend fun import(rawDartJson: String?, verbose: Boolean?)
}

/** Production importer: the process-wide diagnostic logger redacts and merges the Flutter entries. */
internal object AppLoggerLegacyImporter : LegacyLogImporter {
    override suspend fun import(rawDartJson: String?, verbose: Boolean?) = AppLogger.importLegacy(rawDartJson, verbose)
}
