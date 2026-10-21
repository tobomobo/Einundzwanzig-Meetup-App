package space.einundzwanzig.meetup.data.settings

/**
 * Compile-time feature switches (port of `lib/features.dart`). Disabled features keep their
 * code; flipping a flag back on is a one-line change.
 */
object FeatureFlags {
    /**
     * NFC tag reading/writing (Dart `kNfcEnabled`). Disabled since August 2026: the rolling QR is
     * more reliable across devices, and two paths to the same goal double the test effort. While
     * false: request no NFC permission, register no NDEF intent filter (Security Audit N4), and the
     * diagnostics fingerprint logs `NFC: abgeschaltet (features.dart)`.
     */
    const val NFC_ENABLED: Boolean = false

    /**
     * Local nsec login in the browser (Dart `kWebLocalNsecLoginEnabled`, Security Audit H4).
     * Web-only in Flutter; it has no effect on Android and is kept for parity only.
     */
    const val WEB_LOCAL_NSEC_LOGIN_ENABLED: Boolean = false
}
