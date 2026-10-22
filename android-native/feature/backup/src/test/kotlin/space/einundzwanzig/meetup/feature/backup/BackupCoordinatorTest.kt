package space.einundzwanzig.meetup.feature.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.ui.share.ShareResult
import space.einundzwanzig.meetup.data.backup.ExportResult
import space.einundzwanzig.meetup.data.backup.RestoreConfirmation
import space.einundzwanzig.meetup.data.backup.RestoreError
import space.einundzwanzig.meetup.data.backup.RestoreOutcome
import java.io.IOException

class BackupCoordinatorTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.(Harness, MutableList<BackupMessage>) -> Unit) = runTest(dispatcher) {
        val h = Harness(backgroundScope, dispatcher)
        h.coordinator.attach(h.platform)
        val messages = mutableListOf<BackupMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.coordinator.messages.collect { messages += it } }
        block(h, messages)
    }

    private fun TestScope.startExport(h: Harness): Deferred<Boolean> = async { h.coordinator.export() }
    private fun TestScope.startRestore(h: Harness): Deferred<Boolean> = async { h.coordinator.restore() }

    // ------------------------------------------------------------------ export

    @Test
    fun `export saves to the chosen document`() = test { h, messages ->
        val result = startExport(h)
        advanceUntilIdle()
        assertEquals(BackupDialog.Password(isExport = true), h.coordinator.ui.value.dialog)

        h.coordinator.onAction(BackupAction.SubmitPassword("correct horse"))
        advanceUntilIdle()
        assertEquals(listOf("correct horse"), h.exporter.passwords)
        assertNull(h.coordinator.ui.value.dialog)
        assertFalse(h.coordinator.ui.value.loading)
        assertEquals(listOf("Backup_2026-10-21_1200_21MeetupApp_Satoshi.21bkp"), h.platform.created)

        h.coordinator.onDocumentResult("content://docs/1")
        advanceUntilIdle()
        assertTrue(result.await())
        assertEquals("enc_v2:a:b:c", h.documents.written["content://docs/1"])
        assertTrue(h.platform.shared.isEmpty())
        assertTrue(messages.isEmpty())
        assertTrue(h.log.lines.any { it.contains("Backup gespeichert") })
    }

    @Test
    fun `export cancelled at the password dialog does nothing`() = test { h, _ ->
        val result = startExport(h)
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.CancelPassword)
        advanceUntilIdle()
        assertFalse(result.await())
        assertTrue(h.exporter.passwords.isEmpty())
        assertNull(h.coordinator.ui.value.dialog)
        assertFalse(h.coordinator.busy.value)
    }

    @Test
    fun `export ignores a password that breaks the rules`() = test { h, _ ->
        val result = startExport(h)
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.SubmitPassword("short"))
        advanceUntilIdle()
        assertEquals(BackupDialog.Password(isExport = true), h.coordinator.ui.value.dialog)
        assertTrue(h.exporter.passwords.isEmpty())
        h.coordinator.onAction(BackupAction.CancelPassword)
        advanceUntilIdle()
        assertFalse(result.await())
    }

    @Test
    fun `overlay is shown while the file is encrypted`() = test { h, _ ->
        h.exporter.gate = CompletableDeferred()
        val result = startExport(h)
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.SubmitPassword("12345678"))
        advanceUntilIdle()
        assertTrue(h.coordinator.ui.value.loading)
        h.exporter.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(h.coordinator.ui.value.loading)
        h.coordinator.onDocumentResult(null)
        advanceUntilIdle()
        result.await()
    }

    @Test
    fun `cancelled save dialog falls back to the share sheet`() = test { h, _ ->
        val result = startExport(h)
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.SubmitPassword("12345678"))
        advanceUntilIdle()
        h.coordinator.onDocumentResult(null)
        advanceUntilIdle()
        assertTrue(result.await())
        assertEquals("Backup_2026-10-21_1200_21MeetupApp_Satoshi.21bkp", h.platform.shared.single().first)
        assertEquals("enc_v2:a:b:c", h.platform.shared.single().second.decodeToString())
    }

    @Test
    fun `dismissed share sheet returns false, unavailable counts as shared`() = test { h, _ ->
        h.platform.shareResult = ShareResult.Dismissed
        var result = startExport(h)
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.SubmitPassword("12345678"))
        advanceUntilIdle()
        h.coordinator.onDocumentResult(null)
        advanceUntilIdle()
        assertFalse(result.await())

        h.platform.shareResult = ShareResult.Unavailable
        result = startExport(h)
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.SubmitPassword("12345678"))
        advanceUntilIdle()
        h.coordinator.onDocumentResult(null)
        advanceUntilIdle()
        assertTrue(result.await())
    }

    @Test
    fun `failed document write falls back to the share sheet`() = test { h, _ ->
        h.documents.writeError = IOException("disk full")
        val result = startExport(h)
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.SubmitPassword("12345678"))
        advanceUntilIdle()
        h.coordinator.onDocumentResult("content://docs/2")
        advanceUntilIdle()
        assertTrue(result.await())
        assertEquals(1, h.platform.shared.size)
    }

    @Test
    fun `picker that cannot start falls back to share`() = test { h, _ ->
        h.platform.canLaunch = false
        val result = startExport(h)
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.SubmitPassword("12345678"))
        advanceUntilIdle()
        assertTrue(result.await())
        assertEquals(1, h.platform.shared.size)
    }

    @Test
    fun `unreadable badges refuse the export with a snackbar`() = test { h, messages ->
        h.exporter.result = ExportResult.BadgesUnreadable
        val result = startExport(h)
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.SubmitPassword("12345678"))
        advanceUntilIdle()
        assertFalse(result.await())
        assertEquals(listOf<BackupMessage>(BackupMessage.BadgesUnreadable), messages)
        assertTrue(h.platform.created.isEmpty())
        assertFalse(h.coordinator.ui.value.loading)
    }

    @Test
    fun `exporter error shows backupError`() = test { h, messages ->
        h.exporter.error = IllegalStateException("store locked")
        val result = startExport(h)
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.SubmitPassword("12345678"))
        advanceUntilIdle()
        assertFalse(result.await())
        assertEquals(listOf<BackupMessage>(BackupMessage.ExportFailed("store locked")), messages)
        assertFalse(h.coordinator.ui.value.loading)
    }

    @Test
    fun `without a host the export never reports success`() = test { h, _ ->
        h.coordinator.detach(h.platform)
        val result = startExport(h)
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.SubmitPassword("12345678"))
        advanceUntilIdle()
        assertFalse(result.await())
        assertTrue(h.platform.shared.isEmpty())
    }

    @Test
    fun `a second request while busy is ignored silently`() = test { h, _ ->
        val first = startExport(h)
        advanceUntilIdle()
        assertTrue(h.coordinator.busy.value)
        val second = startRestore(h)
        advanceUntilIdle()
        assertFalse(second.await())
        assertEquals(0, h.platform.opened)
        h.coordinator.onAction(BackupAction.CancelPassword)
        advanceUntilIdle()
        assertFalse(first.await())
        assertFalse(h.coordinator.busy.value)
    }

    @Test
    fun `launchExport reports the result`() = test { h, _ ->
        var reported: Boolean? = null
        h.coordinator.launchExport { reported = it }
        // launchExport runs on the app scope (backgroundScope here), which advanceUntilIdle skips.
        runCurrent()
        assertEquals(BackupDialog.Password(isExport = true), h.coordinator.ui.value.dialog)
        h.coordinator.onAction(BackupAction.CancelPassword)
        runCurrent()
        assertEquals(false, reported)
    }

    @Test
    fun `cancelling the caller clears the dialog`() = test { h, _ ->
        val result = startExport(h)
        advanceUntilIdle()
        result.cancel()
        advanceUntilIdle()
        assertNull(h.coordinator.ui.value.dialog)
        assertFalse(h.coordinator.busy.value)
    }

    // ------------------------------------------------------------------ import

    @Test
    fun `cancelled file picker does nothing`() = test { h, messages ->
        val result = startRestore(h)
        advanceUntilIdle()
        assertEquals(1, h.platform.opened)
        h.coordinator.onDocumentResult(null)
        advanceUntilIdle()
        assertFalse(result.await())
        assertTrue(messages.isEmpty())
        assertTrue(h.engine.documents.isEmpty())
        assertTrue(h.log.lines.any { it.contains("Wiederherstellung gestartet") })
    }

    @Test
    fun `plain backup merges and reports the added badges`() = test { h, messages ->
        h.documents.files["content://a"] = VALID_BACKUP
        h.engine.outcome = RestoreOutcome.Merged(3)
        val result = startRestore(h)
        advanceUntilIdle()
        h.coordinator.onDocumentResult("content://a")
        advanceUntilIdle()
        assertTrue(result.await())
        assertNull(h.coordinator.ui.value.dialog) // plain JSON: no password prompt
        assertEquals(5, h.engine.documents.single().version)
        assertEquals(listOf<BackupMessage>(BackupMessage.Merged(3)), messages)
    }

    @Test
    fun `fresh restore reports the restored items`() = test { h, messages ->
        h.documents.files["content://a"] = VALID_BACKUP
        val restored = RestoreOutcome.Restored(badges = 4, nostrKey = true, platformProofs = 2, humanity = false)
        h.engine.outcome = restored
        val result = startRestore(h)
        advanceUntilIdle()
        h.coordinator.onDocumentResult("content://a")
        advanceUntilIdle()
        assertTrue(result.await())
        assertEquals(listOf<BackupMessage>(BackupMessage.Loaded(restored)), messages)
    }

    @Test
    fun `not json and foreign json are rejected`() = test { h, messages ->
        h.documents.files["content://x"] = "hello"
        h.documents.files["content://y"] = """{"foo":1}"""
        for (uri in listOf("content://x", "content://y")) {
            val result = startRestore(h)
            advanceUntilIdle()
            h.coordinator.onDocumentResult(uri)
            advanceUntilIdle()
            assertFalse(result.await())
        }
        assertEquals(
            listOf<BackupMessage>(
                BackupMessage.ImportFailed(ImportFailure.NotValid),
                BackupMessage.ImportFailed(ImportFailure.NotEinundzwanzig),
            ),
            messages,
        )
        assertTrue(h.engine.documents.isEmpty())
    }

    @Test
    fun `unreadable file shows import failed`() = test { h, messages ->
        val result = startRestore(h)
        advanceUntilIdle()
        h.coordinator.onDocumentResult("content://missing")
        advanceUntilIdle()
        assertFalse(result.await())
        assertTrue((messages.single() as BackupMessage.ImportFailed).reason is ImportFailure.Unreadable)
    }

    @Test
    fun `encrypted backup asks for the password and reports a wrong one`() = test { h, messages ->
        h.documents.files["content://enc"] = "enc_v2:broken"
        val result = startRestore(h)
        advanceUntilIdle()
        h.coordinator.onDocumentResult("content://enc")
        advanceUntilIdle()
        assertEquals(BackupDialog.Password(isExport = false), h.coordinator.ui.value.dialog)
        h.coordinator.onAction(BackupAction.SubmitPassword("")) // empty: ignored, dialog stays
        advanceUntilIdle()
        assertEquals(BackupDialog.Password(isExport = false), h.coordinator.ui.value.dialog)
        h.coordinator.onAction(BackupAction.SubmitPassword("x"))
        advanceUntilIdle()
        assertFalse(result.await())
        assertEquals(listOf<BackupMessage>(BackupMessage.WrongPassword), messages)
        assertFalse(h.coordinator.ui.value.loading)
    }

    @Test
    fun `cancelled password on import does nothing`() = test { h, messages ->
        h.documents.files["content://enc"] = "enc_v1:broken"
        val result = startRestore(h)
        advanceUntilIdle()
        h.coordinator.onDocumentResult("content://enc")
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.CancelPassword)
        advanceUntilIdle()
        assertFalse(result.await())
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `replace dialog declined cancels the import`() = test { h, messages ->
        h.documents.files["content://a"] = VALID_BACKUP
        h.engine.confirmation = RestoreConfirmation.Replace(
            existingNpub = "npub1" + "a".repeat(58),
            backupNpub = "npub1" + "b".repeat(58),
        )
        val result = startRestore(h)
        advanceUntilIdle()
        h.coordinator.onDocumentResult("content://a")
        advanceUntilIdle()
        val dialog = h.coordinator.ui.value.dialog as BackupDialog.ConfirmReplace
        assertEquals("npub1aaaaaaa...aaaaaaaa", dialog.existingShort)
        assertEquals("npub1bbbbbbb...bbbbbbbb", dialog.backupShort)
        h.coordinator.onAction(BackupAction.Decline)
        advanceUntilIdle()
        assertFalse(result.await())
        assertTrue(messages.isEmpty())
        assertNull(h.coordinator.ui.value.dialog)
    }

    @Test
    fun `add-unknown dialog confirmed continues the import`() = test { h, messages ->
        h.documents.files["content://a"] = VALID_BACKUP
        h.engine.confirmation = RestoreConfirmation.AddUnknown("npub1" + "c".repeat(58))
        h.engine.outcome = RestoreOutcome.Merged(0)
        val result = startRestore(h)
        advanceUntilIdle()
        h.coordinator.onDocumentResult("content://a")
        advanceUntilIdle()
        assertTrue(h.coordinator.ui.value.dialog is BackupDialog.ConfirmAddUnknown)
        h.coordinator.onAction(BackupAction.Confirm)
        advanceUntilIdle()
        assertTrue(result.await())
        assertEquals(listOf<BackupMessage>(BackupMessage.Merged(0)), messages)
    }

    @Test
    fun `restore errors are reported`() = test { h, messages ->
        h.documents.files["content://a"] = VALID_BACKUP
        h.engine.outcome = RestoreOutcome.Failed(RestoreError.SwitchPending)
        var result = startRestore(h)
        advanceUntilIdle()
        h.coordinator.onDocumentResult("content://a")
        advanceUntilIdle()
        assertFalse(result.await())

        h.engine.error = IllegalStateException("boom")
        result = startRestore(h)
        advanceUntilIdle()
        h.coordinator.onDocumentResult("content://a")
        advanceUntilIdle()
        assertFalse(result.await())
        assertEquals(
            listOf<BackupMessage>(
                BackupMessage.ImportFailed(ImportFailure.Restore(RestoreError.SwitchPending)),
                BackupMessage.ImportFailed(ImportFailure.Unexpected("boom")),
            ),
            messages,
        )
        assertFalse(h.coordinator.busy.value)
    }

    @Test
    fun `restore with invalid content logs the detail`() = test { h, messages ->
        h.documents.files["content://a"] = VALID_BACKUP
        h.engine.outcome = RestoreOutcome.Failed(RestoreError.InvalidContent("badges[0] ist kein Objekt"))
        val result = startRestore(h)
        advanceUntilIdle()
        h.coordinator.onDocumentResult("content://a")
        advanceUntilIdle()
        assertFalse(result.await())
        assertEquals(1, messages.size)
        assertTrue(h.log.lines.any { it.contains("badges[0] ist kein Objekt") })
    }

    @Test
    fun `share sheet whose host is destroyed ends the export`() = test { h, _ ->
        h.platform.canLaunch = false
        h.platform.shareGate = CompletableDeferred()
        val result = startExport(h)
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.SubmitPassword("correct horse"))
        advanceUntilIdle()
        assertEquals(1, h.platform.shared.size)
        assertTrue(h.coordinator.busy.value)

        h.coordinator.detach(h.platform)
        advanceUntilIdle()
        assertFalse(result.await())
        assertFalse(h.coordinator.busy.value)
        assertFalse(h.coordinator.isAttached)
    }

    @Test
    fun `a new host replacing the old one also ends a pending share`() = test { h, _ ->
        h.platform.canLaunch = false
        h.platform.shareGate = CompletableDeferred()
        val result = startExport(h)
        advanceUntilIdle()
        h.coordinator.onAction(BackupAction.SubmitPassword("correct horse"))
        advanceUntilIdle()

        h.coordinator.attach(FakePlatform())
        advanceUntilIdle()
        assertFalse(result.await())
        assertFalse(h.coordinator.busy.value)
        assertTrue(h.coordinator.isAttached)
    }
}
