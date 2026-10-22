package space.einundzwanzig.meetup.feature.settings

/**
 * The NIP-44 probe of the diagnostic log (spec 01 §2.9): `SigningService.canNip44()`.
 * A seam so the ViewModel can be tested without a full signing service.
 */
fun interface Nip44Probe {
    /** `true` if the active signer can NIP-44 encrypt to itself; never throws. */
    suspend fun canNip44(): Boolean
}

/** The app `versionName` for the settings sheet footer (Flutter `PackageInfo.version`). */
fun interface AppVersionSource {
    /** Version name, or `""` if unknown (the footer is then empty, as in Flutter). */
    fun versionName(): String
}
