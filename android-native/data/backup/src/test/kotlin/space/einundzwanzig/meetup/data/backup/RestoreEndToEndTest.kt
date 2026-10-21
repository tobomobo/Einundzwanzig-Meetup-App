package space.einundzwanzig.meetup.data.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.data.backup.internal.BackupProofs
import space.einundzwanzig.meetup.data.backup.internal.DefaultRestoreEngine
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.badgesync.BadgeSync
import space.einundzwanzig.meetup.data.badgesync.BadgeSyncKeys
import space.einundzwanzig.meetup.data.badgesync.RestoreHoldException
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityKeys
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofBackup
import java.io.File
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation

/**
 * The restore engine against the REAL `DefaultBadgeSync` hold/settle state machine.
 *
 * `DefaultBadgeSync` and its ports are internal to `:data:badgesync`, which exposes no test
 * factory, so it is constructed reflectively with `java.lang.reflect.Proxy` ports (suspend
 * functions are called with the trailing `Continuation`). Proofs go through the Flutter pref keys
 * exactly like `PlatformProofRepository.replaceAllRaw` / `HumanityProofRepository.restoreFromBackup`,
 * and the identity switch wipes them with the production `HumanityKeys.clearIdentityProofs`.
 */
class RestoreEndToEndTest {

    @Before
    fun setUp() = runTest { AppLogger.clear() }

    /** Proof writes in the Flutter key layout (`platform_proofs` JSON string, `humanity_*`). */
    private class PrefsProofs(private val prefs: KeyValueStore) : BackupProofs {
        override suspend fun platformProofEntries(): List<JsonObject> = emptyList()
        override suspend fun replacePlatformProofs(validated: List<JsonElement>): Boolean {
            val keep = PlatformProofBackup.restorable(validated)
            return keep.isEmpty() || prefs.edit { setString(PlatformProof.STORAGE_KEY, DartJson.encode(JsonArray(keep))) }
        }
        override suspend fun unionPlatformProofs(validated: List<JsonElement>): Boolean = replacePlatformProofs(validated)
        override suspend fun humanityBackupJson(): JsonObject = JsonObject(emptyMap())
        override suspend fun humanityVerifiedPref(): Boolean = prefs.getBool(HumanityKeys.VERIFIED) == true
        override suspend fun restoreHumanity(proof: JsonObject, additive: Boolean): Boolean {
            if (proof["verified"]?.jsonPrimitive?.booleanOrNull != true) return false
            val receipt = proof["receipt_event_id"]?.jsonPrimitive?.content.orEmpty()
            val firstZap = proof["first_zap_at"]?.jsonPrimitive?.longOrNull ?: 0L
            return prefs.edit {
                setBool(HumanityKeys.VERIFIED, false)
                setBool(HumanityKeys.NEEDS_REVERIFICATION, true)
                if (receipt.isNotEmpty()) setString(HumanityKeys.RECEIPT_ID, receipt)
                if (firstZap > 0) setInt(HumanityKeys.FIRST_ZAP_AT, firstZap)
            }
        }
    }

    private inner class Device(val scope: TestScope) {
        val h = BackupHarness()
        val prefs get() = h.prefs

        /** A new `DefaultBadgeSync` on the same stores (a new process after a crash). */
        fun newSync(): BadgeSync = realBadgeSync(h.prefs, h.repo, h.identity, h.time, scope.backgroundScope)

        fun engine(sync: BadgeSync) =
            DefaultRestoreEngine(h.profiles, h.repo, sync, h.identity, h.admins, PrefsProofs(h.prefs), AppLogger, kotlinx.coroutines.Dispatchers.Unconfined)
    }

    private val sig = "ab".repeat(64)

    private fun backupJson(k: KeyPair) = """{"version":5,"user":{"nickname":"Bob"},"badges":[],
        "nostr":{"nsec":"${k.nsec}","npub":"${k.npub}","priv_hex":"${k.privateKeyHex}","has_key":true,
            "signing_mode":"local","active_npub":"","nip46_bunker_uri":""},
        "platform_proofs":[{"platform":"telegram","username":"bob","proof_sig":"$sig","created_at":1}],
        "humanity_proof":{"verified":true,"first_zap_at":5,"receipt_event_id":"r1","checked_at":6}}"""

    private suspend fun assertProofsKept(prefs: KeyValueStore) {
        assertNotNull(prefs.getString(PlatformProof.STORAGE_KEY))
        assertTrue(prefs.getString(PlatformProof.STORAGE_KEY)!!.contains("telegram"))
        assertEquals(true, prefs.getBool(HumanityKeys.NEEDS_REVERIFICATION))
        assertEquals("r1", prefs.getString(HumanityKeys.RECEIPT_ID))
        assertNull(prefs.getBool(BadgeSyncKeys.RESTORE_HOLD))
        assertNull(prefs.getBool(BadgeSyncKeys.RESTORE_PROOFS))
    }

    @Test
    fun `fresh restore keeps the restored platform and humanity proofs after endRestore`() = runTest {
        val d = Device(this)
        val k = KeyPair.generate()
        val out = d.engine(d.newSync()).restore(doc(backupJson(k)), { true }) as RestoreOutcome.Restored

        assertEquals("Profil, 0 Badges, Nostr-Key, 1 Plattform-Proofs, Humanity-Proof", out.germanItems)
        assertEquals(k.npub, d.h.identity.keyNpub)
        assertProofsKept(d.prefs)
    }

    @Test
    fun `fresh restore keeps the proofs when the process dies before endRestore`() = runTest {
        val d = Device(this)
        val k = KeyPair.generate()
        val real = d.newSync()
        val crashing = object : BadgeSync by real {
            override suspend fun endRestore() = Unit // the process is gone
        }
        assertTrue(d.engine(crashing).restore(doc(backupJson(k)), { true }) is RestoreOutcome.Restored)
        assertEquals(true, d.prefs.getBool(BadgeSyncKeys.RESTORE_HOLD))

        d.newSync().settleRestore() // next start
        assertProofsKept(d.prefs)
    }

    @Test
    fun `fresh restore without a key leaves pre-existing proofs alone`() = runTest {
        val d = Device(this)
        d.prefs.setBool(HumanityKeys.NEEDS_REVERIFICATION, true)
        val out = d.engine(d.newSync()).restore(doc("""{"version":5,"user":{},"badges":[]}"""), { true })
        assertTrue(out is RestoreOutcome.Restored)
        assertEquals(true, d.prefs.getBool(HumanityKeys.NEEDS_REVERIFICATION))
    }

    @Test
    fun `replace restore keeps the proofs of the new identity`() = runTest {
        val d = Device(this)
        val a = KeyPair.generate()
        val b = KeyPair.generate()
        d.h.identity.saveKeys(a.nsec, a.npub, a.privateKeyHex)
        d.prefs.setString(PlatformProof.STORAGE_KEY, "[]")
        val out = d.engine(d.newSync()).restore(doc(backupJson(b)), { true })
        assertTrue(out.toString(), out is RestoreOutcome.Restored)
        assertEquals(b.npub, d.h.identity.keyNpub)
        assertProofsKept(d.prefs)
    }

    @Test
    fun `every RestoreHoldException message of badgesync maps to a typed error`() {
        val source = File(
            "../badgesync/src/main/kotlin/space/einundzwanzig/meetup/data/badgesync/internal/DefaultBadgeSync.kt",
        ).readText()
        val messages = Regex("""RestoreHoldException\(\s*"([^"]+)"""").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(4, messages.size)
        for (m in messages) {
            val mapped = DefaultRestoreEngine.errorOf(RestoreHoldException(m))
            assertTrue("unmapped: $m", mapped in DefaultRestoreEngine.HOLD_ERRORS)
            assertEquals(m, mapped.german)
        }
        assertEquals(DefaultRestoreEngine.HOLD_ERRORS.size, messages.toSet().size)
        assertFalse(DefaultRestoreEngine.errorOf(RestoreHoldException("neu")) in DefaultRestoreEngine.HOLD_ERRORS)
    }

    private companion object {
        /** Calls a suspend lambda for a reflective proxy of a suspend function. */
        @Suppress("UNCHECKED_CAST")
        fun suspendCall(args: Array<out Any?>?, block: suspend () -> Any?): Any? =
            (block as (Continuation<Any?>) -> Any?).invoke(args!!.last() as Continuation<Any?>)

        fun <T> proxy(type: Class<T>, handler: (name: String, args: Array<out Any?>?) -> Any?): T =
            type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                    when (method.name) {
                        "toString" -> "Proxy(${type.simpleName})"
                        "hashCode" -> System.identityHashCode(self)
                        "equals" -> self === args?.get(0)
                        else -> handler(method.name, args)
                    }
                },
            )

        fun realBadgeSync(
            prefs: KeyValueStore,
            repo: BadgeRepository,
            identity: FakeIdentity,
            time: TimeSource,
            scope: CoroutineScope,
        ): BadgeSync {
            val cls = Class.forName("space.einundzwanzig.meetup.data.badgesync.internal.DefaultBadgeSync")
            val ctor = cls.declaredConstructors.single { it.parameterCount == 8 }
            val types = ctor.parameterTypes
            val syncIdentity = proxy(types[2]) { name, args ->
                when (name) {
                    "getSignerReady" -> emptyFlow<Unit>()
                    "npub" -> suspendCall(args) { if (identity.mode.isExternal) identity.externalNpub else identity.keyNpub }
                    "mode" -> suspendCall(args) { identity.mode }
                    "localPrivHex" -> suspendCall(args) { identity.privHex }
                    "pubkeyHex" -> suspendCall(args) { null }
                    "canNip44" -> suspendCall(args) { false }
                    else -> throw UnsupportedOperationException(name)
                }
            }
            val wiper = proxy(types[4]) { name, args ->
                check(name == "clear") { name }
                suspendCall(args) { prefs.edit { HumanityKeys.clearIdentityProofs(this) } }
            }
            val relays = proxy(RelayClient::class.java) { name, _ -> throw java.io.IOException("offline: $name") }
            ctor.isAccessible = true
            return ctor.newInstance(prefs, repo, syncIdentity, relays, wiper, time, scope, AppLogger as DiagnosticLog) as BadgeSync
        }
    }
}
