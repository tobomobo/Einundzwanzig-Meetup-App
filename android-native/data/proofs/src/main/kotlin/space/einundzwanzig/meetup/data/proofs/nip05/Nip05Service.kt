package space.einundzwanzig.meetup.data.proofs.nip05

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Dart `Nip05DomainType`. */
enum class Nip05DomainType(
    /** German label (Dart `Nip05Result.domainLabel`); `""` for [UNKNOWN]. */
    val german: String,
) {
    COMMUNITY("Community-verifiziert"),
    CUSTOM("Eigene Domain"),
    PUBLIC_PROVIDER("Public Provider"),
    UNKNOWN(""),
}

/**
 * Dart `Nip05Result`.
 *
 * @property nip05 the input, unmodified.
 * @property domain the lowercased domain, once the input split into name and domain.
 * @property domainType set whenever `nostr.json` parsed and had a `names` object.
 */
data class Nip05Result(
    val valid: Boolean,
    val nip05: String,
    val domain: String? = null,
    val domainType: Nip05DomainType = Nip05DomainType.UNKNOWN,
) {
    /** Dart `domainLabel`. */
    val domainLabel: String get() = domainType.german
}

/**
 * NIP-05 check of other users' identifiers (Dart `Nip05Service`, spec 06 §5.1, §6.4, §8.6).
 * Used by the reputation layers when checking someone else.
 */
interface Nip05Service {

    /**
     * `GET https://<domain>/.well-known/nostr.json?name=<name>` (name and domain lowercased, URL
     * interpolated without encoding, redirects followed like Flutter), 5 s, body ≤ 64 KiB decoded
     * as UTF-8 regardless of charset. Valid only for HTTP 200, a JSON object with a `names` object
     * whose `names[name]` string equals [expectedPubkeyHex] exactly. Never throws (except
     * cancellation).
     */
    suspend fun verify(nip05: String, expectedPubkeyHex: String): Nip05Result

    /**
     * The `nip05` of the newest kind-0 profile of [pubkeyHex] on the first 2 of [relays]
     * (sequential, 5 s connect + 5 s answer each, sub `nip05-<hex>`). A newer profile without
     * `nip05` erases an older one; timestamps more than 10 min in the future are ignored.
     * @return the value if not empty, else `null`.
     */
    suspend fun fetchNip05FromProfile(pubkeyHex: String, relays: List<String>): String?

    companion object {
        /** Request and relay timeout. */
        val TIMEOUT: Duration = 5.seconds

        /** Body cap of `nostr.json`. */
        const val MAX_BODY_BYTES: Long = 64L * 1024

        /** Community domains (score 1.0); subdomains count. */
        val COMMUNITY_DOMAINS: List<String> = listOf("einundzwanzig.space", "einundzwanzig.ch", "bitcoin.de", "nostr.com")

        /** Public NIP-05 providers (score 0.3); subdomains count. */
        val PUBLIC_PROVIDERS: List<String> = listOf("nostrplebs.com", "nostr.wine", "iris.to", "snort.social", "damus.io")

        /** Domain class of a lowercased [domain]: community, then public provider, else custom. */
        fun classify(domain: String): Nip05DomainType {
            fun matches(d: String) = domain == d || domain.endsWith(".$d")
            return when {
                COMMUNITY_DOMAINS.any(::matches) -> Nip05DomainType.COMMUNITY
                PUBLIC_PROVIDERS.any(::matches) -> Nip05DomainType.PUBLIC_PROVIDER
                else -> Nip05DomainType.CUSTOM
            }
        }

        /** Dart `score`: invalid 0, community 1.0, custom 0.7, public 0.3, unknown 0.2. */
        fun score(result: Nip05Result): Double {
            if (!result.valid) return 0.0
            return when (result.domainType) {
                Nip05DomainType.COMMUNITY -> 1.0
                Nip05DomainType.CUSTOM -> 0.7
                Nip05DomainType.PUBLIC_PROVIDER -> 0.3
                Nip05DomainType.UNKNOWN -> 0.2
            }
        }
    }
}
