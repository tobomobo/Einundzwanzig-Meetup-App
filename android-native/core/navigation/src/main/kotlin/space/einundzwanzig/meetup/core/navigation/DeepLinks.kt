package space.einundzwanzig.meetup.core.navigation

/**
 * Routing for the only external entry points of the app: the tap areas of the home-screen widget
 * (spec 10 §2.10, spec 12 §2.5.10). The Flutter app has no other incoming deep links (its only
 * intent filter is the launcher; NFC/NDEF is off).
 *
 * Each widget area fires an intent with data `homewidget://<target>` and the extra
 * `open=<target>`. The router activity reads `intent.data.host ?: extra "open"`, drops empty
 * targets and `home`, and stores the rest as the pending target (`e21_widget`/`pending_target`) that
 * the app consumes once on start and on every resume. Only `news`, `bitcoin` and `meetup` route
 * anywhere; `home` just opens the app and `refresh` is a background broadcast.
 *
 * Everything here is pure string handling and can run in JVM unit tests.
 */
object DeepLinks {
    /** URI scheme of the widget tap intents. */
    const val WIDGET_SCHEME: String = "homewidget"

    /** Intent extra that carries the target when the intent has no data URI. */
    const val EXTRA_OPEN: String = "open"

    /** Prefix of the per-area intent action, followed by the upper-cased target. */
    const val WIDGET_ACTION_PREFIX: String = "space.einundzwanzig.meetup.WIDGET_"

    /** Widget logo: opens the app without routing. */
    const val TARGET_HOME: String = "home"

    /** Widget news row: opens [News] and marks the news as read. */
    const val TARGET_NEWS: String = "news"

    /** Widget Bitcoin area: opens [BitcoinDashboard]. */
    const val TARGET_BITCOIN: String = "bitcoin"

    /** Widget meetup area: opens [MeetupCalendar] for the next favorite meetup. */
    const val TARGET_MEETUP: String = "meetup"

    /** Widget refresh button: background refresh broadcast, never a navigation target. */
    const val TARGET_REFRESH: String = "refresh"

    /**
     * Maps a widget URI such as `homewidget://news` to its destination.
     *
     * The scheme is matched case-insensitively, the target (the URI host) exactly, as in Flutter.
     * Path, query and fragment are ignored.
     *
     * @return the destination, or `null` for other schemes, malformed URIs, `home`, `refresh` and
     *   unknown targets.
     */
    fun parse(uri: String): Destination? = forWidgetTarget(widgetTargetOf(uri))

    /**
     * Maps a widget target name (URI host, `open` extra or the stored pending target) to its
     * destination, mirroring Flutter's `_routeWidgetTarget`:
     * `news` → [News] with `markAsRead`, `bitcoin` → [BitcoinDashboard],
     * `meetup` → [MeetupCalendar] with `preselectNextFavorite`.
     *
     * @return the destination, or `null` for `null`, `home`, `refresh` and unknown targets.
     */
    fun forWidgetTarget(target: String?): Destination? = when (target) {
        TARGET_NEWS -> News(markAsRead = true)
        TARGET_BITCOIN -> BitcoinDashboard
        TARGET_MEETUP -> MeetupCalendar(preselectNextFavorite = true)
        else -> null
    }

    /**
     * Extracts the target from a widget URI the way the router activity does with
     * `intent.data.host`: the host of a hierarchical `homewidget://` URI.
     *
     * @return the host, or `null` when the scheme is not [WIDGET_SCHEME], the URI is not
     *   hierarchical (`homewidget:news`) or the host is empty.
     */
    fun widgetTargetOf(uri: String): String? {
        val trimmed = uri.trim()
        val schemeEnd = trimmed.indexOf(':')
        if (schemeEnd <= 0) return null
        if (!trimmed.substring(0, schemeEnd).equals(WIDGET_SCHEME, ignoreCase = true)) return null
        if (!trimmed.startsWith("//", startIndex = schemeEnd + 1)) return null
        val authorityStart = schemeEnd + 3
        val authorityEnd = trimmed.indexOfAny(charArrayOf('/', '?', '#'), startIndex = authorityStart)
            .let { if (it < 0) trimmed.length else it }
        val authority = trimmed.substring(authorityStart, authorityEnd)
        val host = authority.substringAfterLast('@').substringBefore(':')
        return host.ifEmpty { null }
    }

    /** Builds the data URI of a widget area, e.g. `homewidget://news`. */
    fun widgetUri(target: String): String {
        require(target.isNotEmpty() && target.none { it in "/?#@:" || it.isWhitespace() }) {
            "invalid widget target '$target'"
        }
        return "$WIDGET_SCHEME://$target"
    }

    /** Builds the intent action of a widget area, e.g. `space.einundzwanzig.meetup.WIDGET_NEWS`. */
    fun widgetAction(target: String): String = WIDGET_ACTION_PREFIX + target.uppercase()
}
