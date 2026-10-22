package space.einundzwanzig.meetup.data.backup

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Real, unchanged Flutter BackupService output, rather than a parallel crypto implementation. */
class FlutterBackupInteropTest {
    @Test fun `Flutter export restores on native and native export retains its identity and wallet`() = runTest {
        val fixtures = File("../../spec/fixtures/backup-interop")
        val source = fixtures.resolve("flutter-v5.21bkp").readText()
        val provenance = DartJson.decode(fixtures.resolve("provenance.json").readText()).jsonObject
        val password = provenance.getValue("password").jsonPrimitive.content
        val opened = BackupCrypto.open(source, password = { password }) as OpenResult.Ok
        assertEquals(fixtures.resolve("flutter-v5.json").readText(), opened.json)

        val target = BackupHarness()
        val outcome = target.engine.restore(doc(opened.json)) { error("Fresh restore needs no identity confirmation") }
        assertEquals(RestoreOutcome.Restored(2, true, null, false), outcome)
        assertEquals("Interop Ada", target.profiles.stored.nickname)
        assertEquals("Änne 😀", target.profiles.stored.fullName)
        assertEquals(listOf("Aschaffenburg", "Würzburg"), target.profiles.stored.favoriteMeetupIds)
        assertEquals("0".repeat(63) + "1", target.identity.privHex)
        assertTrue(RestorePolicy.backupKeyConsistent(
            target.identity.nsec.orEmpty(), target.identity.keyNpub.orEmpty(), target.identity.privHex.orEmpty(),
        ))
        val badges = target.repo.loadBadges()
        assertEquals(setOf("interop-meetup", "interop-event"), badges.map { it.id }.toSet())
        assertTrue(badges.single { it.id == "interop-event" }.isEvent)
        assertFalse(badges.single { it.id == "interop-event" }.presenceVerified)
        assertEquals(918000L, badges.single { it.id == "interop-meetup" }.blockHeight)

        val exported = target.exporter.export(password) as ExportResult.Ready
        val roundTrip = BackupCrypto.open(exported.content, password = { password }) as OpenResult.Ok
        val native = doc(roundTrip.json)
        val flutter = doc(opened.json)
        for (section in listOf("user", "badges", "nostr", "platform_proofs", "humanity_proof")) {
            assertEquals(section, flutter.data[section], native.data[section])
        }
        // Opt-in regeneration for the real Flutter importer test. Ordinary unit runs never
        // mutate checked-in fixtures. The file contains only the public scalar-1 test identity.
        System.getenv("E21_BACKUP_INTEROP_DIR")?.let { output ->
            File(output).apply { mkdirs() }.resolve("kotlin-v5.21bkp").writeText(exported.content)
        }
    }
}
