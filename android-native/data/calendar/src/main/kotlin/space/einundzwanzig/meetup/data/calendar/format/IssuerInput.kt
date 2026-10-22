package space.einundzwanzig.meetup.data.calendar.format

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.calendar.internal.dartLower

/** Badge-helper input rules of the event editor and the "Helfer bearbeiten" sheet (spec 10 §2.3, §2.4). */
object IssuerInput {

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Editor row → hex (Dart `_toHex`): `npub1…` decoded (must give 64 chars) or 64 hex chars,
     * lowercased; blank or anything else → `null`.
     */
    fun toHex(raw: String): String? {
        val s = raw.dartTrim()
        if (s.isEmpty()) return null
        if (s.startsWith("npub1")) {
            return try {
                Nip19.decodeNpub(s).takeIf { it.length == 64 }?.dartLower()
            } catch (_: Exception) {
                null
            }
        }
        return if (HEX64.matches(s)) s.dartLower() else null
    }

    /** A row shows the red border only when it is non-blank and not convertible. */
    fun isRowInvalid(raw: String): Boolean = raw.dartTrim().isNotEmpty() && toHex(raw) == null

    /** Valid rows, deduplicated, in row order (invalid rows are ignored at publish). */
    fun issuersOf(rows: List<String>): List<String> = rows.mapNotNull(::toHex).distinct()

    /** Outcome of adding a helper in the edit sheet. */
    sealed interface AddResult {
        /** Add [hex] and clear the input. */
        data class Added(val hex: String) : AddResult

        /** **evIssuersInvalid**. */
        data object Invalid : AddResult

        /** **evIssuersDuplicate** (the creator or already listed). */
        data object Duplicate : AddResult
    }

    /**
     * Edit-sheet add rule: input starting with `npub` is NIP-19 decoded, anything else lowercased;
     * decode failure or a length ≠ 64 → invalid. Unlike Dart, a 64-char non-npub input must also
     * be hex (spec 10 §2.3 "port SHOULD require"). The creator and listed keys → duplicate.
     */
    fun addHelper(raw: String, creatorPubkey: String, current: List<String>): AddResult {
        val s = raw.dartTrim()
        val hex = try {
            if (s.startsWith("npub")) Nip19.decodeNpub(s) else s.dartLower()
        } catch (_: Exception) {
            return AddResult.Invalid
        }
        if (hex.length != 64 || !HEX64.matches(hex)) return AddResult.Invalid
        if (hex == creatorPubkey.dartLower() || hex in current) return AddResult.Duplicate
        return AddResult.Added(hex)
    }

    /** Initial helper list of the edit sheet: issuers lowercased, without the creator. */
    fun initialHelpers(issuers: List<String>, creatorPubkey: String): List<String> {
        val creator = creatorPubkey.dartLower()
        return issuers.map { it.dartLower() }.filter { it != creator }
    }
}
