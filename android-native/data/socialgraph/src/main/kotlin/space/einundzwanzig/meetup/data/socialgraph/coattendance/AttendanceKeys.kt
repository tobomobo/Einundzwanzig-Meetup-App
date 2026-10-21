package space.einundzwanzig.meetup.data.socialgraph.coattendance

import space.einundzwanzig.meetup.data.badges.text.DartString

/**
 * Attendance keys of the co-attendance network (kind 30079 `d` tag = `content.event`).
 * Byte-exact port of the key helpers in `coattendance_service.dart` (spec 05 §7.3, §8.2.1,
 * §8.2.2; spec 00 item 5). Pure, no Android dependencies.
 *
 * Formats on the wire:
 * - `<meetupEventId>@<last 12 chars of signer npub>` (meetups, current format)
 * - `<meetupEventId>` (legacy, signer unknown)
 * - `ev|<meetupEventId>` (event badges; never create edges)
 */
object AttendanceKeys {

    /** Prefix of event attendances. */
    const val EVENT_PREFIX = "ev|"

    /** Number of trailing signer-npub characters in a meetup key. */
    const val SIGNER_SUFFIX_LENGTH = 12

    // `\z` instead of `$`: Java's `$` also matches before a final line terminator, Dart's does not.
    private val DATE_ONLY = Regex("""^\d{4}-\d{2}-\d{2}\z""")
    private val TRAILING_DATE = Regex("""-\d{4}-\d{2}-\d{2}\z""")
    private val PLACEHOLDERS = listOf("unbekanntes-meetup", "unknown-meetup", "meetup-desconocido")
    private val GENERIC_NAMES = setOf(
        "test", "test1", "test2", "test3", "testing", "demo",
        "home", "garten", "ab-test", "probe", "temp", "tmp", "xxx",
    )

    /** `true` for event keys (`ev|…`). */
    fun isEventKey(key: String): Boolean = key.startsWith(EVENT_PREFIX)

    /**
     * Dart `CoAttendanceService.attendanceKey`: `ev|<id>` for events, `<id>@<last 12 of signer>`
     * for meetups, or the plain id when the (Dart-trimmed) signer is empty.
     */
    fun attendanceKey(meetupEventId: String, signerNpub: String, isEvent: Boolean = false): String {
        if (isEvent) return "$EVENT_PREFIX$meetupEventId"
        val signer = DartString.trim(signerNpub)
        if (signer.isEmpty()) return meetupEventId
        val short = if (signer.length > SIGNER_SUFFIX_LENGTH) signer.substring(signer.length - SIGNER_SUFFIX_LENGTH) else signer
        return "$meetupEventId@$short"
    }

    /** The part before the first `@` (Dart `_base`). */
    fun base(key: String): String {
        val i = key.indexOf('@')
        return if (i < 0) key else key.substring(0, i)
    }

    /**
     * Dart `sharedKeys`: equal keys match; if exactly one side lacks the `@signer` suffix the
     * bases are compared and the **base** is returned; two different suffixes never match.
     * Insertion-ordered result.
     */
    fun sharedKeys(a: Set<String>, b: Set<String>): Set<String> {
        if (a.isEmpty() || b.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        for (x in a) {
            val xb = base(x)
            val xHasSigner = x.length != xb.length
            for (y in b) {
                if (x == y) {
                    out.add(x)
                    continue
                }
                val yb = base(y)
                if (xb != yb) continue
                val yHasSigner = y.length != yb.length
                if (!xHasSigner || !yHasSigner) out.add(xb)
            }
        }
        return out
    }

    /** Dart `_keysMatch`: the [sharedKeys] rule for two single keys. */
    fun keysMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val ab = base(a)
        val bb = base(b)
        if (ab != bb) return false
        return a.length == ab.length || b.length == bb.length
    }

    /** Dart `_wantedKeys`: every key plus its non-empty base if it had a suffix (the `#d` values). */
    fun wantedKeys(keys: Iterable<String>): Set<String> {
        val wanted = LinkedHashSet<String>()
        for (k in keys) {
            wanted.add(k)
            val b = base(k)
            if (b.length != k.length && b.isNotEmpty()) wanted.add(b)
        }
        return wanted
    }

    /**
     * Dart `_isDegenerateEventId`: ids that name no real meetup (empty, leading `-`, translated
     * "unknown meetup" placeholders, date only) and generic legacy names without signer suffix.
     */
    fun isDegenerateEventId(id: String): Boolean {
        val v = DartString.lower(DartString.trim(id))
        if (v.isEmpty()) return true
        if (v.startsWith("-")) return true
        if (PLACEHOLDERS.any { v.startsWith(it) }) return true
        if (DATE_ONLY.containsMatchIn(v)) return true
        if (!v.contains('@')) {
            val namePart = v.replace(TRAILING_DATE, "")
            if (namePart in GENERIC_NAMES) return true
        }
        return false
    }
}
