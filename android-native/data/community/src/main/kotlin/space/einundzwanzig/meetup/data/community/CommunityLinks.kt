package space.einundzwanzig.meetup.data.community

/** External targets of the community hub and PlebRap screens (spec 11 §2.5, §2.11). */
object CommunityLinks {
    /** Hub "Nostr" tile: Einundzwanzig profile on njump. */
    const val NOSTR_PROFILE: String =
        "https://njump.me/npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc"

    /** Hub "Shoutout" tile. */
    const val SHOUTOUT: String = "https://shoutout.einundzwanzig.space"

    /** Hub "Podcast" tile. */
    const val PODCAST: String = "https://einundzwanzig.space/podcast/"

    /** SatoshiDuell without auto-login. */
    const val SATOSHI_DUELL: String = "https://satoshiduell.de/"

    /** PlebRap player AppBar action "Sats an die Künstler" (`prV4V`). */
    const val PLEBRAP_V4V: String = "https://plebrap.de/V4V/"

    /**
     * SatoshiDuell card target: `https://satoshiduell.de/?npub=<npub>` (auto-login) when the user
     * has an npub, else [SATOSHI_DUELL]. The npub is inserted verbatim, as in Flutter.
     */
    fun satoshiDuell(npub: String?): String =
        if (!npub.isNullOrEmpty()) "https://satoshiduell.de/?npub=$npub" else SATOSHI_DUELL
}
