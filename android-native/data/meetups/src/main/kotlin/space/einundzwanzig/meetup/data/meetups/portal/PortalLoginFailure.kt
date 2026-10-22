package space.einundzwanzig.meetup.data.meetups.portal

/** Legacy login diagnostics remain stable; presentation localizes the fixed envelope/cause. */
object PortalLoginFailure {
    const val PREFIX = "Anmeldung fehlgeschlagen: "
    const val TOKEN_NOT_STORED = "Token konnte nicht gespeichert werden"

    fun cause(error: String?): String = error.orEmpty().removePrefix(PREFIX)
}
