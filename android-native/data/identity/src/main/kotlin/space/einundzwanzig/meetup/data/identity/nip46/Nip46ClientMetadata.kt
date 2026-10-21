package space.einundzwanzig.meetup.data.identity.nip46

/** Public display hints sent to remote signers (Dart `Nip46ClientMetadata`). Byte-identical. */
object Nip46ClientMetadata {
    /** App name. */
    const val NAME: String = "Einundzwanzig Meetup"

    /** Project URL. */
    const val URL: String = "https://github.com/louisthecat86/Einundzwanzig-Meetup-App"

    /** App icon. The literal `%40` (an encoded `@` in the GitHub path) is intentional. */
    const val IMAGE: String =
        "https://raw.githubusercontent.com/louisthecat86/Einundzwanzig-Meetup-App/main/ios/Runner/" +
            "Assets.xcassets/AppIcon.appiconset/Icon-App-60x60%403x.png"

    /** `{"name","url","image"}` in this insertion order (sent as JSON in `connect`). */
    val values: Map<String, String> = linkedMapOf("name" to NAME, "url" to URL, "image" to IMAGE)
}
