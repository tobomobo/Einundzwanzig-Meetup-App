package space.einundzwanzig.meetup.feature.network

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.ui.nostr.NpubFormat
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.socialgraph.coattendance.AttendanceKeyLabel
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkContact
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure display rules of "Meine Vernetzung" (spec 05 §2.1, Dart `my_network_screen.dart`).
 * Everything here is free of Compose so it can be unit tested on the JVM.
 */
internal object NetworkPresentation {

    /** `_listPreview`: entries per degree section before "Alle {n} anzeigen". */
    const val LIST_PREVIEW = 20

    /** `_graphDirect`: direct contacts drawn on the inner ring. */
    const val GRAPH_DIRECT = 8

    /** `_maxNames`: names requested per batch. */
    const val MAX_NAMES = 400

    /** Chips in the "auch erreichbar über" section of the detail sheet. */
    const val ALSO_VIA_MAX = 12

    /** `NpubChip.shorten(npub, head: 10, tail: 8)`. */
    fun short(npub: String): String = NpubFormat.shorten(npub, head = 10, tail = 8)

    /** Dart `_label`: [you] for the user, else the name, else the short npub. */
    fun label(npub: String, myNpub: String, names: Map<String, String>, you: String): String =
        if (npub == myNpub) you else names[npub] ?: short(npub)

    /** Dart `_initial`: first code point of the trimmed name, upper-cased; `""` without a name. */
    fun initialOf(name: String?): String {
        val n = name?.dartTrim()
        if (n.isNullOrEmpty()) return ""
        val cp = n.codePointAt(0)
        return DartString.upper(String(Character.toChars(cp)))
    }

    /** `mnStandAt` time: `HH:mm` on the same local day as [nowMillis], else `dd.MM. HH:mm`. */
    fun standTime(at: Instant, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = at.atZone(zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        fun two(v: Int) = v.toString().padStart(2, '0')
        val hm = "${two(t.hour)}:${two(t.minute)}"
        return if (t.toLocalDate() == now.toLocalDate()) {
            hm
        } else {
            "${AttendanceKeyLabel.dateText(t.toLocalDate(), withYear = false)} $hm"
        }
    }

    /** `mnUpdating` degree: `clamp(doneDepth + 1, 1, 3)`. */
    fun updatingDegree(doneDepth: Int): Int = (doneDepth + 1).coerceIn(1, 3)

    /** One degree section of the list. */
    data class Section(
        val degree: Int,
        val total: Int,
        val shown: List<NetworkContact>,
    ) {
        val hasMore: Boolean get() = shown.size < total
    }

    /** Dart sections: filter by degree chip and case-insensitive name/npub search. */
    fun sections(
        net: MyNetwork,
        names: Map<String, String>,
        filter: Int,
        query: String,
        expanded: Set<Int>,
    ): List<Section> {
        val q = DartString.lower(query.dartTrim())
        val out = mutableListOf<Section>()
        for (d in 1..3) {
            if (filter != 0 && filter != d) continue
            val list = (net.byDegree[d] ?: emptyList()).filter { c ->
                if (q.isEmpty()) return@filter true
                DartString.lower(c.npub).contains(q) ||
                    (names[c.npub]?.let { DartString.lower(it).contains(q) } ?: false)
            }
            if (list.isEmpty()) continue
            val showAll = q.isNotEmpty() || d in expanded
            out += Section(d, list.size, if (showAll) list else list.take(LIST_PREVIEW))
        }
        return out
    }

    /** The localized words the detail line is made of. */
    interface Labels {
        val you: String
        val and: String
        val oneSharedMeetup: String
        fun sharedMeetups(count: Int): String
        fun lastAt(place: String): String
        fun via(names: String): String
    }

    /** Dart `_detailLine`: the "how you know them" line of a contact row. */
    fun detailLine(net: MyNetwork, c: NetworkContact, names: Map<String, String>, l: Labels): String {
        fun lbl(n: String) = label(n, net.myNpub, names, l.you)
        if (c.degree == 1) {
            val n = c.sharedMeetupsWithMe.size
            if (n == 0) return ""
            val first = if (n == 1) l.oneSharedMeetup else l.sharedMeetups(n)
            val newest = AttendanceKeyLabel.newestFirst(c.sharedMeetupsWithMe).first()
            val d = AttendanceKeyLabel.date(newest)
            val place = AttendanceKeyLabel.place(newest)
            val at = if (d == null) place else "$place, ${AttendanceKeyLabel.dateText(d, withYear = false)}"
            return "$first · ${l.lastAt(at)}"
        }
        if (c.degree == 3) {
            val path = net.pathTo(c.npub)
            if (path.size >= 3) {
                return l.via(path.subList(1, path.size - 1).joinToString(" → ") { lbl(it) })
            }
        }
        val bridges = c.bridges.map { lbl(it) }.sorted()
        return when (bridges.size) {
            0 -> ""
            1 -> l.via(bridges[0])
            2 -> l.via("${bridges[0]} ${l.and} ${bridges[1]}")
            else -> l.via("${bridges[0]}, ${bridges[1]} +${bridges.size - 2}")
        }
    }

    /** Detail sheet path: `pathTo`, or `[me, npub]` for a direct contact without one. */
    fun detailPath(net: MyNetwork, npub: String): List<String> {
        val path = net.pathTo(npub)
        if (path.isEmpty() && net.contactsByNpub[npub]?.degree == 1) return listOf(net.myNpub, npub)
        return path
    }

    /** "Auch erreichbar über": bridges not on [path], sorted by label, at most 12. */
    fun alsoVia(net: MyNetwork, npub: String, path: List<String>, names: Map<String, String>, you: String): List<String> {
        val c = net.contactsByNpub[npub] ?: return emptyList()
        val onPath = path.toSet()
        return c.bridges.filter { it !in onPath }
            .sortedBy { label(it, net.myNpub, names, you) }
            .take(ALSO_VIA_MAX)
    }

    /** npubs whose names should be requested next (Dart `_loadNames` selection). */
    fun namesToAsk(net: MyNetwork, asked: Set<String>, toHex: (String) -> String?): Map<String, String> {
        val hexOf = LinkedHashMap<String, String>()
        for (npub in net.contactsByNpub.keys) {
            if (hexOf.size >= MAX_NAMES) break
            if (npub in asked) continue
            toHex(npub)?.let { hexOf[npub] = it }
        }
        return hexOf
    }

    // ---------------------------------------------------------------- tree graph

    /**
     * One drawn node. [degree] 0 = the user. Coordinates are pixels within the canvas.
     *
     * @property fromX start of the line to the BFS parent (`null` for the user).
     */
    data class TreeNode(
        val npub: String,
        val degree: Int,
        val x: Float,
        val y: Float,
        val radius: Float,
        val fromX: Float? = null,
        val fromY: Float? = null,
        val initial: String = "",
    )

    /** Dart `_layoutTree`, exact; [density] converts Flutter logical pixels (dp) to px. */
    fun layoutTree(net: MyNetwork, names: Map<String, String>, w: Float, h: Float, density: Float): List<TreeNode> {
        val cx = w / 2f
        val cy = h / 2f
        val out = mutableListOf(TreeNode(net.myNpub, 0, cx, cy, 19f * density))
        val direct = (net.byDegree[1] ?: emptyList()).take(GRAPH_DIRECT)
        if (direct.isEmpty()) return out
        val childrenOf = LinkedHashMap<String, MutableList<NetworkContact>>()
        for (d in 2..3) {
            for (c in net.byDegree[d] ?: emptyList()) {
                val p = c.parent ?: continue
                childrenOf.getOrPut(p) { mutableListOf() }.add(c)
            }
        }
        val maxR = min(w, h) / 2f
        val r1 = 0.48 * maxR
        val r2 = 0.77 * maxR
        val r3 = 0.93 * maxR
        val sector = 2 * PI / direct.size
        val spread = sector * (if (direct.size <= 3) 0.22 else 0.32)
        direct.forEachIndexed { i, c1 ->
            val a = -PI / 2 + i * sector
            val p1x = (cx + r1 * cos(a)).toFloat()
            val p1y = (cy + r1 * sin(a)).toFloat()
            out += TreeNode(c1.npub, 1, p1x, p1y, 15f * density, cx, cy, initialOf(names[c1.npub]))
            val kids = (childrenOf[c1.npub] ?: emptyList<NetworkContact>()).take(3)
            kids.forEachIndexed { j, kid ->
                val a2 = a + (j - (kids.size - 1) / 2.0) * spread
                val p2x = (cx + r2 * cos(a2)).toFloat()
                val p2y = (cy + r2 * sin(a2)).toFloat()
                out += TreeNode(kid.npub, kid.degree, p2x, p2y, 6f * density, p1x, p1y)
                val grand = (childrenOf[kid.npub] ?: emptyList<NetworkContact>()).take(2)
                grand.forEachIndexed { q, g ->
                    val a3 = a2 + (q - (grand.size - 1) / 2.0) * 0.16
                    out += TreeNode(
                        g.npub, g.degree,
                        (cx + r3 * cos(a3)).toFloat(), (cy + r3 * sin(a3)).toFloat(),
                        3.5f * density, p2x, p2y,
                    )
                }
            }
        }
        return out
    }

    /** Nearest node (not the user) within [tolerance] px of the tap, or `null`. */
    fun hitTest(nodes: List<TreeNode>, x: Float, y: Float, tolerance: Float): TreeNode? {
        var best: TreeNode? = null
        var bestD = tolerance
        for (n in nodes) {
            if (n.degree == 0) continue
            val dx = n.x - x
            val dy = n.y - y
            val d = sqrt(dx * dx + dy * dy)
            if (d <= bestD) {
                bestD = d
                best = n
            }
        }
        return best
    }

    /**
     * Dart `_targetNpub`: the first highlighted node (not the user) that has no highlighted
     * successor of greater degree drawn from it; `null` without a highlight.
     */
    fun targetNpub(nodes: List<TreeNode>, highlight: Set<String>): String? {
        if (highlight.isEmpty()) return null
        for (n in nodes) {
            if (n.degree == 0 || n.npub !in highlight) continue
            val hasNext = nodes.any { m ->
                m.fromX == n.x && m.fromY == n.y && m.npub in highlight && m.degree > n.degree
            }
            if (!hasNext) return n.npub
        }
        return null
    }
}
