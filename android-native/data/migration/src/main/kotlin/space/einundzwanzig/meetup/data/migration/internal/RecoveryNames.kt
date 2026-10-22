package space.einundzwanzig.meetup.data.migration.internal

/**
 * `SecureStore` recovery names of spec 14 §4.8 (`<prefix>/<flutter key>`). The prefixes are the
 * spec's wire names and must stay equal to `RecoverySource.prefix` in `:data:identity` and
 * `IdentityKeys.RECOVERY_PREFIXES` (which `IdentityRecovery` and the badge candidate scan read).
 * Kept local so `:data:migration` does not depend on `:data:identity`: the identity domain has to
 * be able to consult the migration (pending secrets, gate) without a module cycle.
 */
internal enum class RecoveryNames(val prefix: String) {
    /** Old EncryptedSharedPreferences value that did not become primary. */
    ESP("legacy_esp"),

    /** A `_BACKUP` copy that differed from the primary value. */
    BACKUP("legacy_backup"),

    /** A Flutter value that could not take its own name (native value present, or a third distinct value). */
    RECOVERED("legacy_recovered"),
    ;

    /** `<prefix>/<key>`. */
    fun of(key: String): String = "$prefix/$key"

    companion object {
        /**
         * Separator of an overflow slot (`legacy_recovered/<key>#2`, `#3`, …), used only when the
         * plain recovery name already holds a different value: a parked value is never overwritten.
         */
        const val OVERFLOW_SEPARATOR = "#"

        /** Highest overflow index tried before giving up (the write then counts as failed). */
        const val MAX_OVERFLOW_SLOTS = 16

        /** The Flutter key a recovery name (plain or overflow slot) belongs to. */
        fun keyOf(name: String): String = name.substringAfter('/').substringBefore(OVERFLOW_SEPARATOR)
    }
}
