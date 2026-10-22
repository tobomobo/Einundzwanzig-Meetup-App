package space.einundzwanzig.meetup.feature.backup

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.backup.RestoreError
import space.einundzwanzig.meetup.data.backup.RestoreOutcome

@RunWith(AndroidJUnit4::class)
class BackupMessagesTest {

    private val res: Resources get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun `restored items match Flutter's German list`() {
        val all = RestoreOutcome.Restored(badges = 7, nostrKey = true, platformProofs = 3, humanity = true)
        assertEquals(all.germanItems, BackupMessages.restoredItems(res, all))
        val minimal = RestoreOutcome.Restored(badges = 0, nostrKey = false, platformProofs = null, humanity = false)
        assertEquals(minimal.germanItems, BackupMessages.restoredItems(res, minimal))
        assertEquals(
            "✅ Backup geladen! Profil, 7 Badges, Nostr-Key, 3 Plattform-Proofs, Humanity-Proof wiederhergestellt.",
            BackupMessages.snackbar(res, BackupMessage.Loaded(all)).text,
        )
    }

    @Test
    fun `merge messages`() {
        val added = BackupMessages.snackbar(res, BackupMessage.Merged(2))
        assertEquals("2 Badges hinzugefügt", added.text)
        assertEquals(Tone.Success, added.tone)
        assertEquals(4_000L, added.durationMillis)
        assertEquals("Sammlung zusammengeführt, keine neuen Badges", BackupMessages.snackbar(res, BackupMessage.Merged(0)).text)
    }

    @Test
    fun `error messages`() {
        assertEquals(
            Tone.Error to "Falsches Passwort oder Datei beschädigt!",
            BackupMessages.snackbar(res, BackupMessage.WrongPassword).let { it.tone to it.text },
        )
        assertEquals(
            "Fehler beim Backup: disk full",
            BackupMessages.snackbar(res, BackupMessage.ExportFailed("disk full")).text,
        )
        assertEquals(
            "Import fehlgeschlagen: Datei ist kein gültiges Backup oder das falsche Format.",
            BackupMessages.snackbar(res, BackupMessage.ImportFailed(ImportFailure.NotValid)).text,
        )
        assertEquals(
            "Import fehlgeschlagen: Datei ist kein gültiges Einundzwanzig Backup.",
            BackupMessages.snackbar(res, BackupMessage.ImportFailed(ImportFailure.NotEinundzwanzig)).text,
        )
        val unreadable = BackupMessages.snackbar(res, BackupMessage.BadgesUnreadable)
        assertEquals(AppSnackbarDurations.ExtraLong, unreadable.durationMillis)
        assertEquals(res.getString(space.einundzwanzig.meetup.core.l10n.R.string.badgesUnreadableBody), unreadable.text)
    }

    @Test
    fun `every restore error has the Flutter German text`() {
        val errors = listOf(
            RestoreError.SwitchPending, RestoreError.HoldNotPersisted, RestoreError.ClearNotMarked,
            RestoreError.ProofsNotCleared, RestoreError.ProofsNotSaved, RestoreError.StageFailed(3), RestoreError.KeyUnreadable,
            RestoreError.KeyNotTaken, RestoreError.BadgesNotSaved(2), RestoreError.Other("io"),
        )
        errors.forEach { assertEquals(it.german, BackupMessages.restoreErrorText(res, it)) }
    }

    @Test
    fun `technical restore details are replaced by localized texts`() {
        assertEquals(
            "Datei ist kein gültiges Backup oder das falsche Format.",
            BackupMessages.restoreErrorText(res, RestoreError.InvalidContent("badges[1] ist kein Objekt")),
        )
        assertEquals(
            "Die Datei konnte nicht gelesen werden.",
            BackupMessages.failureText(res, ImportFailure.Unexpected(" ")),
        )
        assertEquals("boom", BackupMessages.failureText(res, ImportFailure.Unexpected("boom")))
    }

    @Test
    fun `a count of one uses the singular (deviation from Flutter)`() {
        val one = RestoreOutcome.Restored(badges = 1, nostrKey = false, platformProofs = 1, humanity = false)
        assertEquals("Profil, 1 Badge, 1 Plattform-Proof", BackupMessages.restoredItems(res, one))
    }

    @Test
    @Config(qualifiers = "en")
    fun `english items`() {
        val r = RestoreOutcome.Restored(badges = 1, nostrKey = true, platformProofs = null, humanity = false)
        assertEquals("✅ Backup loaded! Profile, 1 badge, Nostr key restored.", BackupMessages.snackbar(res, BackupMessage.Loaded(r)).text)
    }

    @Test
    fun `restore error counts use plurals`() {
        assertEquals(
            "1 Badge aus dem Backup konnte nicht vorbereitet werden.",
            BackupMessages.restoreErrorText(res, RestoreError.StageFailed(1)),
        )
        assertEquals(
            "1 Badge aus dem Backup konnte nicht gespeichert werden, der Rest wurde übernommen.",
            BackupMessages.restoreErrorText(res, RestoreError.BadgesNotSaved(1)),
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun `incomplete proof restore uses an actionable English error`() {
        val message = BackupMessages.snackbar(res, BackupMessage.ImportFailed(ImportFailure.Restore(RestoreError.ProofsNotSaved)))
        assertEquals(Tone.Error, message.tone)
        assertTrue(message.text.contains("Some proofs from the backup could not be saved."))
        assertTrue(message.text.contains("Load the backup again."))
    }

    @Test
    @Config(qualifiers = "es")
    fun `incomplete proof restore uses an actionable Spanish error`() {
        val message = BackupMessages.snackbar(res, BackupMessage.ImportFailed(ImportFailure.Restore(RestoreError.ProofsNotSaved)))
        assertEquals(Tone.Error, message.tone)
        assertTrue(message.text.contains("No se pudieron guardar algunas pruebas"))
        assertTrue(message.text.contains("Carga la copia de seguridad de nuevo."))
    }

    @Test
    @Config(qualifiers = "en")
    fun `english restore error counts use plurals`() {
        assertEquals("1 badge from the backup could not be prepared.", BackupMessages.restoreErrorText(res, RestoreError.StageFailed(1)))
        assertEquals("3 badges from the backup could not be prepared.", BackupMessages.restoreErrorText(res, RestoreError.StageFailed(3)))
        assertEquals(
            "1 badge from the backup could not be saved. Everything else was restored.",
            BackupMessages.restoreErrorText(res, RestoreError.BadgesNotSaved(1)),
        )
    }

    @Test
    @Config(qualifiers = "es")
    fun `spanish restore error counts use plurals`() {
        assertEquals(
            "No se pudo preparar 1 insignia de la copia de seguridad.",
            BackupMessages.restoreErrorText(res, RestoreError.StageFailed(1)),
        )
        assertEquals(
            "No se pudieron guardar 2 insignias de la copia de seguridad. Todo lo demás se restauró.",
            BackupMessages.restoreErrorText(res, RestoreError.BadgesNotSaved(2)),
        )
    }
}
