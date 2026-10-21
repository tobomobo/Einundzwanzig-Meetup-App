package space.einundzwanzig.meetup.data.backup

import space.einundzwanzig.meetup.core.crypto.DartUtf8

/**
 * Builds and encrypts the `.21bkp` export (Dart `BackupService.createBackup` without UI, spec 08
 * §2.4 steps 4–9, §3.2, §8.1). The feature layer owns the password dialog, overlay, SAF/share
 * targets and the single-flight busy flag; it writes [ExportResult.Ready.content] with
 * [BackupFiles.write].
 */
interface BackupExporter {

    /**
     * The backup JSON, version 5, byte-compatible with Flutter's `jsonEncode` (key order of
     * spec 08 §3.2, ints as ints, local `timestamp`). Refuses with [BuildResult.BadgesUnreadable]
     * when the badge wallet cannot be read: an empty list must never enter a backup (the reset flow
     * treats a successful export as permission to wipe).
     *
     * @throws Exception from the stores (e.g. an unreadable secure store); show `backupError`.
     */
    suspend fun buildDocument(): BuildResult

    /**
     * [buildDocument] + `enc_v2` encryption with [password] (PBKDF2 600 000 on the default
     * dispatcher, ~1–2 s) + the suggested file name.
     *
     * @return [ExportResult.PasswordTooShort] if [password] breaks [BackupPasswordRules] (the dialog
     *   prevents that), [ExportResult.BadgesUnreadable], or the ready file.
     * @throws Exception like [buildDocument].
     */
    suspend fun export(password: String): ExportResult
}

/** Result of [BackupExporter.buildDocument]. */
sealed interface BuildResult {
    /**
     * @property json the plaintext backup (contains the nsec: never log it).
     * @property nickname the profile nickname (for [BackupFileName]).
     */
    class Ok(val json: String, val nickname: String) : BuildResult {
        override fun toString(): String = "Ok(<${json.length} chars>)"
    }

    /** The wallet is unreadable: show `badgesUnreadableBody` (floating, 8 s) and export nothing. */
    data object BadgesUnreadable : BuildResult
}

/** Result of [BackupExporter.export]. */
sealed interface ExportResult {
    /**
     * @property fileName suggested name (`Backup_<yyyy-MM-dd_HHmm>_21MeetupApp_<nick>.21bkp`).
     * @property content the `enc_v2:…` file text (ASCII).
     */
    class Ready(val fileName: String, val content: String) : ExportResult {
        /** UTF-8 bytes of [content] (for a share-cache file). */
        val bytes: ByteArray get() = DartUtf8.encode(content)

        override fun toString(): String = "Ready($fileName)"
    }

    /** See [BuildResult.BadgesUnreadable]. */
    data object BadgesUnreadable : ExportResult

    /** The password is shorter than [BackupPasswordRules.MIN_PASSWORD_LENGTH]. */
    data object PasswordTooShort : ExportResult
}
