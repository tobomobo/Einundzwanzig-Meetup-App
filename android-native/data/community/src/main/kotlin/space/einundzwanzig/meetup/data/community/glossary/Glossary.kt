package space.einundzwanzig.meetup.data.community.glossary

import androidx.annotation.StringRes
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.data.community.internal.dartLowerCase

/**
 * Glossary categories in display order (`GlossaryCategory` in `lib/screens/glossary_screen.dart`).
 * [labelRes] is the ARB label (`glCat*`). Icons (Material rounded) per spec 11 §2.12: Start flag,
 * Badges military_tech, Reputation workspace_premium, Network hub, Identity key, Events
 * celebration, Nostr bolt, App tune.
 */
enum class GlossaryCategory(@StringRes val labelRes: Int) {
    Start(L10nR.string.glCatStart),
    Badges(L10nR.string.glCatBadges),
    Reputation(L10nR.string.glCatReputation),
    Network(L10nR.string.glCatNetwork),
    Identity(L10nR.string.glCatIdentity),
    Events(L10nR.string.glCatEvents),
    Nostr(L10nR.string.glCatNostr),
    App(L10nR.string.glCatApp),
}

/** One glossary entry: localized [titleRes] / [bodyRes] (`gl<Name>Title` / `gl<Name>Body`). */
data class GlossaryEntry(
    val category: GlossaryCategory,
    @param:StringRes @get:StringRes val titleRes: Int,
    @param:StringRes @get:StringRes val bodyRes: Int,
)

/** An entry with its strings resolved for the current locale (search runs on these). */
data class ResolvedGlossaryEntry(val entry: GlossaryEntry, val title: String, val body: String) {
    val category: GlossaryCategory get() = entry.category
}

/** The 55 glossary entries in Flutter order (spec 11 §2.12). The text lives in `:core:l10n`. */
object Glossary {

    val entries: List<GlossaryEntry> = buildList {
        fun entry(c: GlossaryCategory, title: Int, body: Int) = add(GlossaryEntry(c, title, body))
        val s = GlossaryCategory.Start
        entry(s, L10nR.string.glWhatIsAppTitle, L10nR.string.glWhatIsAppBody)
        entry(s, L10nR.string.glCollectTitle, L10nR.string.glCollectBody)
        entry(s, L10nR.string.glHomeMeetupTitle, L10nR.string.glHomeMeetupBody)
        entry(s, L10nR.string.glNicknameTitle, L10nR.string.glNicknameBody)
        entry(s, L10nR.string.glFindMeetupTitle, L10nR.string.glFindMeetupBody)
        entry(s, L10nR.string.glOfflineTitle, L10nR.string.glOfflineBody)

        val b = GlossaryCategory.Badges
        entry(b, L10nR.string.glBadgeProofTitle, L10nR.string.glBadgeProofBody)
        entry(b, L10nR.string.glRollingQrTitle, L10nR.string.glRollingQrBody)
        entry(b, L10nR.string.glOnSiteTitle, L10nR.string.glOnSiteBody)
        entry(b, L10nR.string.glBlockHeightTitle, L10nR.string.glBlockHeightBody)
        entry(b, L10nR.string.glChecksumTitle, L10nR.string.glChecksumBody)
        entry(b, L10nR.string.glDuplicateTitle, L10nR.string.glDuplicateBody)
        entry(b, L10nR.string.glBadgeShareTitle, L10nR.string.glBadgeShareBody)
        entry(b, L10nR.string.glWorldMapTitle, L10nR.string.glWorldMapBody)

        val r = GlossaryCategory.Reputation
        entry(r, L10nR.string.glTrustScoreTitle, L10nR.string.glTrustScoreBody)
        entry(r, L10nR.string.glLevelsTitle, L10nR.string.glLevelsBody)
        entry(r, L10nR.string.glHumanityTitle, L10nR.string.glHumanityBody)
        entry(r, L10nR.string.glPlatformsTitle, L10nR.string.glPlatformsBody)
        entry(r, L10nR.string.glPublishTitle, L10nR.string.glPublishBody)
        entry(r, L10nR.string.glVerifyPersonTitle, L10nR.string.glVerifyPersonBody)
        entry(r, L10nR.string.glRepCardTitle, L10nR.string.glRepCardBody)

        val n = GlossaryCategory.Network
        entry(n, L10nR.string.glEncounterTitle, L10nR.string.glEncounterBody)
        entry(n, L10nR.string.glDegreesTitle, L10nR.string.glDegreesBody)
        entry(n, L10nR.string.glVouchTitle, L10nR.string.glVouchBody)
        entry(n, L10nR.string.glTrustPathTitle, L10nR.string.glTrustPathBody)
        entry(n, L10nR.string.glEventNetTitle, L10nR.string.glEventNetBody)
        entry(n, L10nR.string.glDistrustTitle, L10nR.string.glDistrustBody)
        entry(n, L10nR.string.glOrganizerTitle, L10nR.string.glOrganizerBody)

        val i = GlossaryCategory.Identity
        entry(i, L10nR.string.glKeysTitle, L10nR.string.glKeysBody)
        entry(i, L10nR.string.glFindKeysTitle, L10nR.string.glFindKeysBody)
        entry(i, L10nR.string.glPasswordTitle, L10nR.string.glPasswordBody)
        entry(i, L10nR.string.glSignerTitle, L10nR.string.glSignerBody)
        entry(i, L10nR.string.glNcryptsecTitle, L10nR.string.glNcryptsecBody)
        entry(i, L10nR.string.glPasskeyTitle, L10nR.string.glPasskeyBody)
        entry(i, L10nR.string.glNip05Title, L10nR.string.glNip05Body)
        entry(i, L10nR.string.glImportTitle, L10nR.string.glImportBody)
        entry(i, L10nR.string.glBackupTitle, L10nR.string.glBackupBody)
        entry(i, L10nR.string.glRestoreTitle, L10nR.string.glRestoreBody)

        val e = GlossaryCategory.Events
        entry(e, L10nR.string.glCalendarSourcesTitle, L10nR.string.glCalendarSourcesBody)
        entry(e, L10nR.string.glCreateEventTitle, L10nR.string.glCreateEventBody)
        entry(e, L10nR.string.glPortalTitle, L10nR.string.glPortalBody)
        entry(e, L10nR.string.glSpecialEventTitle, L10nR.string.glSpecialEventBody)
        entry(e, L10nR.string.glEventHelperTitle, L10nR.string.glEventHelperBody)
        entry(e, L10nR.string.glEventWindowTitle, L10nR.string.glEventWindowBody)

        val no = GlossaryCategory.Nostr
        entry(no, L10nR.string.glNostrBasicsTitle, L10nR.string.glNostrBasicsBody)
        entry(no, L10nR.string.glRelaysTitle, L10nR.string.glRelaysBody)
        entry(no, L10nR.string.glPublicTitle, L10nR.string.glPublicBody)
        entry(no, L10nR.string.glZapTitle, L10nR.string.glZapBody)
        entry(no, L10nR.string.glNewsTitle, L10nR.string.glNewsBody)
        entry(no, L10nR.string.glCommunityTitle, L10nR.string.glCommunityBody)
        entry(no, L10nR.string.glConverterTitle, L10nR.string.glConverterBody)

        val a = GlossaryCategory.App
        entry(a, L10nR.string.glTilesTitle, L10nR.string.glTilesBody)
        entry(a, L10nR.string.glLanguageTitle, L10nR.string.glLanguageBody)
        entry(a, L10nR.string.glLogTitle, L10nR.string.glLogBody)
        entry(a, L10nR.string.glResetTitle, L10nR.string.glResetBody)
    }

    /** Resolves every entry with [string] (typically `context::getString` / `stringResource`). */
    fun resolve(string: (Int) -> String): List<ResolvedGlossaryEntry> =
        entries.map { ResolvedGlossaryEntry(it, string(it.titleRes), string(it.bodyRes)) }

    /** `query.trim().toLowerCase()` as the screen uses it (Dart case mapping, see resolution #12). */
    fun normalizeQuery(query: String): String = query.dartTrim().dartLowerCase()

    /**
     * Search of the Glossary screen: a blank query returns everything; otherwise entries whose
     * title or body contains the normalized query (case-insensitive). Order is preserved.
     */
    fun search(entries: List<ResolvedGlossaryEntry>, query: String): List<ResolvedGlossaryEntry> {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return entries
        return entries.filter { it.title.dartLowerCase().contains(q) || it.body.dartLowerCase().contains(q) }
    }

    /** Hits grouped by category in enum order; categories without hits are omitted. */
    fun groupByCategory(hits: List<ResolvedGlossaryEntry>): Map<GlossaryCategory, List<ResolvedGlossaryEntry>> {
        val byCategory = hits.groupBy { it.category }
        return GlossaryCategory.entries.mapNotNull { c -> byCategory[c]?.let { c to it } }.toMap(LinkedHashMap())
    }
}
