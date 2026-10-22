package space.einundzwanzig.meetup.data.identity.integrity

/**
 * Root indicators (Dart `DeviceIntegrityService`, spec 01 §8.8, Security Audit M5): warn, never
 * block. The home screen shows [warningMessage] (localize it in the feature) when
 * [IntegrityReport.isCompromised].
 */
interface DeviceIntegrityService {
    /** Runs the checks once per process (cached) unless [forceRefresh]. */
    suspend fun check(forceRefresh: Boolean = false): IntegrityReport

    /** The Flutter banner text (German). */
    val warningMessage: String get() = WARNING_MESSAGE

    companion object {
        const val WARNING_MESSAGE = "Dieses Gerät zeigt Anzeichen von Root/Jailbreak. Kryptographische Schlüssel könnten " +
            "weniger geschützt sein. Für maximale Sicherheit ein nicht modifiziertes Gerät verwenden."
    }
}

/** Result of [DeviceIntegrityService.check]. */
enum class DeviceIntegrityStatus {
    /** No findings. */
    SECURE,

    /** Root indicators found. */
    WARNING,

    /** The check failed. */
    UNKNOWN,
}

/**
 * @property findings German finding lines (`Root-Binary gefunden: <path>`, …).
 * @property checkedAtMillis wall-clock time of the check.
 */
data class IntegrityReport(
    val status: DeviceIntegrityStatus,
    val findings: List<String>,
    val checkedAtMillis: Long,
) {
    /** `status == WARNING`. */
    val isCompromised: Boolean get() = status == DeviceIntegrityStatus.WARNING
}
