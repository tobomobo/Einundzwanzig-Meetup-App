package space.einundzwanzig.meetup.data.proofs.platform

import space.einundzwanzig.meetup.data.badges.text.DartString

/** Icon key of a platform (Dart `PlatformInfo.icon`). The feature maps it to a Material icon. */
enum class PlatformIcon(
    /** The Dart icon key (`shopping_cart`, `send`, ...). */
    val key: String,
) {
    SHOPPING_CART("shopping_cart"),
    SEND("send"),
    SMART_TOY("smart_toy"),
    HUB("hub"),
    LANGUAGE("language"),

    /** Any unknown key (Dart `_iconFor` default). */
    LINK("link"),
    ;

    companion object {
        /** Dart `_iconFor(key)`: unknown keys give [LINK]. */
        fun of(key: String): PlatformIcon = entries.firstOrNull { it.key == key } ?: LINK
    }
}

/**
 * One entry of the platform catalogue (Dart `PlatformInfo`, spec 06 §2.6/§3.1).
 *
 * [name] and [hint] are the hard-coded German texts of the Flutter app; `:core:l10n` has no
 * resources for them. `:feature:proofs` owns the localized texts (`native_`-prefixed resources
 * in DE/EN/ES whose German defaults equal [name]/[hint]) and maps [id] to its own `R.string`
 * ids, so no resource keys cross the module boundary. Brand names (all but
 * [PlatformCatalog.OTHER]) are not translated.
 */
data class PlatformInfo(
    /** Stored platform id (wire value in proofs, verify strings and the reputation event). */
    val id: String,
    /** German display name (`Andere Plattform` for [PlatformCatalog.OTHER]). */
    val name: String,
    val icon: PlatformIcon,
    /** German username hint. */
    val hint: String,
)

/** The ordered platform catalogue (Dart `PlatformProofService.platforms`). */
object PlatformCatalog {

    /** Id of the "custom platform" chip. Custom proofs are stored under their own id. */
    const val OTHER: String = "other"

    /** Order matters (chip order): satoshikleinanzeigen, telegram, robosats, nostr, other. */
    val platforms: List<PlatformInfo> = listOf(
        PlatformInfo(
            id = "satoshikleinanzeigen",
            name = "Satoshi-Kleinanzeigen",
            icon = PlatformIcon.SHOPPING_CART,
            hint = "Dein Benutzername auf Satoshi-Kleinanzeigen",
        ),
        PlatformInfo(
            id = "telegram",
            name = "Telegram",
            icon = PlatformIcon.SEND,
            hint = "Dein Telegram @username (ohne @)",
        ),
        PlatformInfo(
            id = "robosats",
            name = "RoboSats",
            icon = PlatformIcon.SMART_TOY,
            hint = "Dein RoboSats Robot-Name",
        ),
        PlatformInfo(
            id = "nostr",
            name = "Nostr",
            icon = PlatformIcon.HUB,
            hint = "Dein Nostr-Profilname (NIP-05 oder Display Name)",
        ),
        PlatformInfo(
            id = OTHER,
            name = "Andere Plattform",
            icon = PlatformIcon.LANGUAGE,
            hint = "Dein Benutzername auf der Plattform",
        ),
    )

    private val byId: Map<String, PlatformInfo> = platforms.associateBy { it.id }

    /** The catalogue entry for [id], or `null` for custom platforms. */
    fun byId(id: String): PlatformInfo? = byId[id]

    /** Display name: catalogue name, or the raw id for custom platforms (Dart proof card). */
    fun displayName(id: String): String = byId(id)?.name ?: id

    /** Icon: catalogue icon, or [PlatformIcon.LANGUAGE] for custom platforms (Dart proof card). */
    fun iconFor(id: String): PlatformIcon = byId(id)?.icon ?: PlatformIcon.LANGUAGE

    /**
     * Dart `customPlatformName.toLowerCase().replaceAll(' ', '_')`: Dart case mapping
     * (byte-exact, the id is signed), only U+0020 is replaced. The caller passes the name
     * already trimmed (the screen does).
     */
    fun customPlatformId(customName: String): String = DartString.lower(customName).replace(" ", "_")
}
