package space.einundzwanzig.meetup.feature.wallet

import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.feature.wallet.domain.BadgeFormat
import space.einundzwanzig.meetup.feature.wallet.domain.WalletLibrary

/** Sample data for previews. */
internal object PreviewData {
    private const val NOW_MILLIS = 1_792_000_000_000L

    fun badges(): List<MeetupBadge> = listOf(
        badge("p1", "Aschaffenburg", "2026-09-12T19:00:00.000", 915_000, 49.97, 9.15, sigVersion = 2),
        badge("p2", "Aschaffenburg", "2026-08-08T19:00:00.000", 910_500, 49.97, 9.15),
        badge("p3", "München, DE", "2025-11-20T19:00:00.000", 872_100, 48.14, 11.58, presenceVerified = false),
        badge("p4", "Wien", "2026-03-02T18:30:00.000", 888_000, 48.21, 16.37),
        badge("p5", "Zürich", "2025-06-15T18:00:00.000", 850_123, 47.37, 8.54),
        badge("p6", "Aschaffenburg", "2026-07-11T19:00:00.000", 905_000, 49.97, 9.15),
        badge("p7", "Berlin", "2026-10-01T19:00:00.000", 918_000, 52.52, 13.40, isOrganizer = true),
    )

    fun walletState(groupByMeetup: Boolean = true, compact: Boolean = false): WalletUiState {
        val all = badges()
        return WalletUiState(
            loaded = true,
            badges = all,
            groupByMeetup = groupByMeetup,
            compact = compact,
            groups = WalletLibrary.groups(all, groupByMeetup).map { GroupUi(it, BadgeFormat.collectionSubtitle(it.badges, NOW_MILLIS)) },
        )
    }

    private fun badge(
        id: String,
        name: String,
        date: String,
        block: Long,
        lat: Double,
        lng: Double,
        sigVersion: Long = 0,
        presenceVerified: Boolean = true,
        isOrganizer: Boolean = false,
    ) = MeetupBadge(
        id = id,
        meetupName = name,
        date = DartDateTime.parse(date),
        iconPath = "",
        blockHeight = block,
        signerNpub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
        meetupEventId = "evt-$id",
        delivery = "rolling_qr",
        sigVersion = sigVersion,
        sigId = if (sigVersion == 2L) "c".repeat(64) else "",
        presenceVerified = presenceVerified,
        isOrganizer = isOrganizer,
        lat = lat,
        lng = lng,
    )
}
