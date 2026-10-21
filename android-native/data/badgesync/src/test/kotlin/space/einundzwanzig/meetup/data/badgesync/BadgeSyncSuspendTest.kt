package space.einundzwanzig.meetup.data.badgesync

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeStoreKeys

private const val NPUB_A = "npub1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val NPUB_B = "npub1bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
private const val NPUB_C = "npub1ccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

/**
 * Port of `test/badge_sync_suspend_test.dart` (all 18 cases, spec 08 §10.7): no sync while a
 * backup is imported, one replay afterwards; what happens to the staging buffer of an identity
 * switch is decided by the keystore, not by a flag. "Process dies" = a new [SyncHarness.newProcess]
 * instance on the same stores (Dart `debugResetProcess`). Without identity a run ends at once
 * (no signer) but still counts.
 */
class BadgeSyncSuspendTest {

    private fun badge(id: String) = MeetupBadge(
        id = id,
        meetupName = "Aschaffenburg",
        meetupEventId = id,
        date = DartDateTime.utc(2026, 10, 11, 19),
        iconPath = "assets/badge_icon.png",
    )

    private fun test(body: suspend TestScope.(SyncHarness) -> Unit) = runTest { body(SyncHarness(this)) }

    private suspend fun SyncHarness.ids() = repo.loadBadges().map { it.id }

    private suspend fun SyncHarness.incoming() = prefs.getString(BadgeStoreKeys.INCOMING)

    private suspend fun SyncHarness.hold() = prefs.getBool(BadgeSyncKeys.RESTORE_HOLD)

    @Test
    fun `ohne Riegel laeuft jeder Ausloeser`() = test { h ->
        h.sync.trigger()
        runCurrent() // background work (trigger) included
        assertEquals(1, h.sync.runs.get())
    }

    @Test
    fun `ausgesetzt - kein Lauf, am Ende genau einer`() = test { h ->
        h.sync.beginRestoreHold()
        h.sync.trigger()
        h.sync.trigger()
        runCurrent() // background work (trigger) included
        assertEquals("während des Backups kein Sync", 0, h.sync.runs.get())

        h.sync.endRestore()
        runCurrent() // background work (trigger) included
        assertEquals("zwei Auslöser, ein Nachlauf", 1, h.sync.runs.get())
    }

    @Test
    fun `Ende ohne Ausloeser startet nichts`() = test { h ->
        h.sync.beginRestoreHold()
        h.sync.endRestore()
        runCurrent() // background work (trigger) included
        assertEquals(0, h.sync.runs.get())
    }

    @Test
    fun `Riegel ohne Identitaetswechsel - Puffer wird verworfen, Datei bleibt`() = test { h ->
        h.repo.saveBadges(listOf(badge("bleibt")))
        // No identity before, none after: nothing switched.
        h.sync.beginRestoreHold()
        assertTrue(h.repo.stageIncoming(listOf(badge("neu"))))
        val restarted = h.newProcess() // the process dies, the hold is in the prefs

        restarted.settleRestore()

        assertNull(h.incoming())
        assertNotEquals(true, h.hold())
        assertEquals(listOf("bleibt"), h.ids())
    }

    @Test
    fun `Riegel mit Identitaetswechsel - Puffer wird uebernommen`() = test { h ->
        // Identity A with a collection before, then the keystore holds B.
        h.identity.npub = NPUB_B
        h.repo.saveBadges(listOf(badge("alt")))
        h.sync.beginRestoreHold(fromNpub = NPUB_A)
        assertTrue(h.repo.stageIncoming(listOf(badge("neu"))))
        h.prefs.setStringList(BadgeSyncKeys.CHUNK_HASHES, listOf("x"))
        val restarted = h.newProcess()

        restarted.settleRestore()

        assertEquals(listOf("neu"), h.ids())
        assertNull(h.incoming())
        assertNotEquals(true, h.hold())
        assertNull("Hashes der alten Identität vergessen", h.prefs.getStringList(BadgeSyncKeys.CHUNK_HASHES))
        // The old collection is not gone, it is in the archive.
        val former = h.repo.formerCollections()
        assertEquals("replaced", former.single().reason)
        assertEquals(NPUB_A, former.single().npub)
        assertEquals(listOf("alt"), former.single().badges?.map { it.id })
    }

    @Test
    fun `npub nicht lesbar gilt nicht als Wechsel`() = test { h ->
        h.repo.saveBadges(listOf(badge("bleibt")))
        h.sync.beginRestoreHold(fromNpub = NPUB_A)
        assertTrue(h.repo.stageIncoming(listOf(badge("neu"))))
        val restarted = h.newProcess()

        restarted.settleRestore()

        assertEquals(true, h.hold())
        assertNotNull(h.incoming())
        assertEquals(listOf("bleibt"), h.ids())
        assertFalse(restarted.identitySwitched(NPUB_A, ""))
        assertTrue(restarted.identitySwitched(NPUB_A, NPUB_B))
    }

    @Test
    fun `offener Wechsel blockiert ein Einspielen, das nicht ersetzt`() = test { h ->
        h.repo.saveBadges(listOf(badge("bleibt")))
        h.sync.beginRestoreHold(fromNpub = NPUB_A)
        assertTrue(h.repo.stageIncoming(listOf(badge("fremd"))))
        val restarted = h.newProcess()
        // The npub is not readable: the hold stays.
        restarted.settleRestore()

        try {
            restarted.beginRestoreHold(fromNpub = "")
            fail("expected RestoreHoldException")
        } catch (e: RestoreHoldException) {
            assertTrue(e.message!!.startsWith("Ein Identitätswechsel ist noch nicht abgeschlossen."))
        }
        assertEquals(true, h.hold())
        assertNotNull(h.incoming())
        assertEquals(listOf("bleibt"), h.ids())
    }

    @Test
    fun `Leeren-Vormerk verwirft einen aelteren Puffer`() = test { h ->
        h.sync.beginRestoreHold(fromNpub = NPUB_A)
        assertTrue(h.repo.stageIncoming(listOf(badge("fremd"))))
        h.sync.markRestoreClear()
        assertNull(h.incoming())
        assertEquals(true, h.prefs.getBool(BadgeSyncKeys.RESTORE_CLEAR))
    }

    @Test
    fun `andere Identitaet als die versprochene uebernimmt den Puffer nicht`() = test { h ->
        h.identity.npub = NPUB_C
        h.repo.saveBadges(listOf(badge("alt")))
        h.sync.beginRestoreHold(fromNpub = NPUB_A, expectNpubs = listOf(NPUB_B))
        assertTrue(h.repo.stageIncoming(listOf(badge("neu"))))
        val restarted = h.newProcess()

        restarted.settleRestore()

        assertEquals(true, h.hold())
        assertNotNull(h.incoming())
        assertEquals(listOf("alt"), h.ids())
    }

    @Test
    fun `offener Wechsel - Ersetzen darf weiter und behaelt den Ursprung`() = test { h ->
        h.repo.saveBadges(listOf(badge("alt")))
        h.sync.beginRestoreHold(fromNpub = NPUB_A, expectNpubs = listOf(NPUB_B), replace = true)
        assertTrue(h.repo.stageIncoming(listOf(badge("neu"))))
        // The key arrived only half: npub B, privHex does not match.
        h.identity.npub = NPUB_B
        h.identity.privHex = "ab"
        val restarted = h.newProcess()
        restarted.settleRestore()
        assertEquals(true, h.hold())
        assertEquals("die Sammlung gehört noch A", NPUB_A, restarted.pendingFrom())

        // Replace may continue: from stays A, the buffer stays until the new one.
        restarted.beginRestoreHold(fromNpub = "egal", expectNpubs = listOf(NPUB_B), replace = true)
        assertEquals(NPUB_A, h.prefs.getString(BadgeSyncKeys.RESTORE_FROM))
        assertNotNull(h.incoming())
        assertTrue(h.repo.stageIncoming(listOf(badge("neu2"))))
        // This time the key is complete.
        h.identity.privHex = null

        restarted.endRestore()
        assertNotEquals(true, h.hold())
        assertEquals(listOf("neu2"), h.ids())
        val former = h.repo.formerCollections()
        assertEquals(NPUB_A, former.single().npub)
        assertEquals(listOf("alt"), former.single().badges?.map { it.id })
    }

    @Test
    fun `Rueckfall auf den lokalen Schluessel des Backups zaehlt als Wechsel`() = test { h ->
        // Mixed backup: active B, local key C. The external signer did not come, the keystore holds C.
        h.identity.npub = NPUB_C
        h.repo.saveBadges(listOf(badge("alt")))
        h.sync.beginRestoreHold(fromNpub = NPUB_A, expectNpubs = listOf(NPUB_B, NPUB_C), replace = true)
        assertTrue(h.repo.stageIncoming(listOf(badge("neu"))))
        val restarted = h.newProcess()

        restarted.settleRestore()

        assertNotEquals(true, h.hold())
        assertEquals(listOf("neu"), h.ids())
    }

    @Test
    fun `privater Schluessel muss zum npub passen`() = test { h ->
        h.repo.saveBadges(listOf(badge("alt")))
        h.identity.npub = NPUB_B
        h.identity.privHex = "ab"
        h.sync.beginRestoreHold(fromNpub = NPUB_A, expectNpubs = listOf(NPUB_B))
        assertTrue(h.repo.stageIncoming(listOf(badge("neu"))))
        val restarted = h.newProcess()

        restarted.settleRestore()

        assertEquals(true, h.hold())
        assertEquals(listOf("alt"), h.ids())
    }

    @Test
    fun `Wechsel raeumt Nachweise der alten Identitaet`() = test { h ->
        h.identity.npub = NPUB_B
        h.repo.saveBadges(listOf(badge("alt")))
        h.prefs.setBool("humanity_verified", true)
        h.prefs.setString("platform_proofs", """[{"platform":"a"}]""")
        h.sync.beginRestoreHold(fromNpub = NPUB_A, expectNpubs = listOf(NPUB_B))
        assertTrue(h.repo.stageIncoming(listOf(badge("neu"))))
        val restarted = h.newProcess()

        restarted.settleRestore()

        assertEquals(false, h.prefs.getBool("humanity_verified"))
        assertNull(h.prefs.getString("platform_proofs"))
        assertNotEquals(true, h.prefs.getBool(BadgeSyncKeys.RESTORE_PROOFS))
        assertEquals(listOf("neu"), h.ids())
    }

    @Test
    fun `schon geschriebene Nachweise der neuen Identitaet bleiben`() = test { h ->
        h.identity.npub = NPUB_B
        h.repo.saveBadges(listOf(badge("alt")))
        h.sync.beginRestoreHold(fromNpub = NPUB_A, expectNpubs = listOf(NPUB_B))
        assertTrue(h.repo.stageIncoming(listOf(badge("neu"))))
        h.sync.clearIdentityProofs()
        h.prefs.setBool("humanity_verified", true)
        h.prefs.setString("platform_proofs", """[{"platform":"b"}]""")
        val restarted = h.newProcess()

        restarted.settleRestore()

        assertEquals(true, h.prefs.getBool("humanity_verified"))
        assertEquals("""[{"platform":"b"}]""", h.prefs.getString("platform_proofs"))
    }

    @Test
    fun `neues Einspielen uebernimmt erst, was der Keystore schon entschied`() = test { h ->
        h.identity.npub = NPUB_B
        h.repo.saveBadges(listOf(badge("alt")))
        h.sync.beginRestoreHold(fromNpub = NPUB_A)
        assertTrue(h.repo.stageIncoming(listOf(badge("neu"))))
        // Crash after the key, before the promotion.
        val restarted = h.newProcess()

        restarted.beginRestoreHold(fromNpub = NPUB_B)
        assertEquals(listOf("neu"), h.ids())
        val former = h.repo.formerCollections()
        assertEquals(NPUB_A, former.single().npub)
        assertEquals(listOf("alt"), former.single().badges?.map { it.id })
    }

    @Test
    fun `leeres Ersetzen raeumt erst, wenn die Identitaet wirklich neu ist`() = test { h ->
        h.repo.saveBadges(listOf(badge("alt")))
        h.sync.beginRestoreHold(fromNpub = NPUB_A)
        h.sync.markRestoreClear()
        val restarted = h.newProcess()

        restarted.settleRestore()
        assertEquals("ohne lesbaren npub bleibt der Riegel", true, h.hold())
        assertEquals(listOf("alt"), h.ids())

        h.identity.npub = NPUB_B
        restarted.settleRestore()
        assertTrue(h.repo.loadBadges().isEmpty())
        assertNotEquals(true, h.hold())
        assertNotEquals(true, h.prefs.getBool(BadgeSyncKeys.RESTORE_CLEAR))
        val former = h.repo.formerCollections()
        assertEquals(listOf("alt"), former.single().badges?.map { it.id })
    }

    @Test
    fun `laufendes Einspielen im selben Prozess - settle ruehrt nichts an`() = test { h ->
        h.sync.beginRestoreHold(fromNpub = NPUB_A)
        assertTrue(h.repo.stageIncoming(listOf(badge("neu"))))
        h.sync.settleRestore()
        assertNotNull(h.incoming())
        assertEquals(true, h.hold())
    }

    @Test
    fun `forgetLocalState raeumt Hashes, Generation und dirty`() = test { h ->
        h.prefs.setStringList(BadgeSyncKeys.CHUNK_HASHES, listOf("x"))
        h.prefs.setInt(BadgeSyncKeys.LAST_GEN, 5)
        h.prefs.setBool(BadgeSyncKeys.DIRTY, true)
        h.sync.forgetLocalState()
        assertNull(h.prefs.getStringList(BadgeSyncKeys.CHUNK_HASHES))
        assertNull(h.prefs.getInt(BadgeSyncKeys.LAST_GEN))
        assertNull(h.prefs.getBool(BadgeSyncKeys.DIRTY))
    }

    // ------------------------------------------------------------------ native additions

    @Test
    fun `legacy string in badge_restore_expect is tolerated`() = test { h ->
        h.identity.npub = NPUB_C
        h.repo.saveBadges(listOf(badge("alt")))
        h.sync.beginRestoreHold(fromNpub = NPUB_A)
        assertTrue(h.repo.stageIncoming(listOf(badge("neu"))))
        h.prefs.set(BadgeSyncKeys.RESTORE_EXPECT, PrefValue.StringValue(" ${NPUB_B.uppercase()} "))
        h.newProcess().settleRestore()
        assertEquals("C is not the expected B", true, h.hold())
        assertEquals(listOf("alt"), h.ids())
    }

    @Test
    fun `hold that cannot be persisted throws and stays unset`() = test { h ->
        h.prefs.failingKeys = setOf(BadgeSyncKeys.RESTORE_HOLD)
        try {
            h.sync.beginRestoreHold(fromNpub = NPUB_A)
            fail("expected RestoreHoldException")
        } catch (e: RestoreHoldException) {
            assertEquals("Der Wechsel konnte nicht festgehalten werden.", e.message)
        }
        assertNull(h.hold())
        assertNull(h.prefs.getString(BadgeSyncKeys.RESTORE_FROM))
        // Not suspended: a trigger runs.
        h.sync.trigger()
        runCurrent() // background work (trigger) included
        assertEquals(1, h.sync.runs.get())
    }

    @Test
    fun `beginRestoreHold normalises from and expect like Dart`() = test { h ->
        h.sync.beginRestoreHold(fromNpub = "  ${NPUB_A.uppercase()} ", expectNpubs = listOf(" $NPUB_B", "", NPUB_B.uppercase(), NPUB_C))
        assertEquals(NPUB_A, h.prefs.getString(BadgeSyncKeys.RESTORE_FROM))
        assertEquals(listOf(NPUB_B, NPUB_C), h.prefs.getStringList(BadgeSyncKeys.RESTORE_EXPECT))
        // Dart parity: after a restart nothing is staged or marked, so settling lifts the hold.
        assertNull(h.newProcess().pendingFrom())
    }
}
