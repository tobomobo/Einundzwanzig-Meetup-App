package space.einundzwanzig.meetup.feature.badges.nfc

import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.ui.util.findActivity
import space.einundzwanzig.meetup.feature.badges.R
import javax.inject.Inject

/** NFC hardware state (Dart `NfcManager.checkAvailability`). */
enum class NfcAvailability {
    /** No NFC hardware (`nfcUnavailable` dialog). */
    NOT_SUPPORTED,

    /** NFC switched off (`nfcDisabled` dialog). */
    DISABLED,

    /** Ready for reader mode. */
    ENABLED,
}

/** Reads the device's NFC state and opens the NFC settings. */
interface NfcStatusSource {
    /** Current availability; never prompts. */
    fun availability(): NfcAvailability

    /** Opens `Settings.ACTION_NFC_SETTINGS` (Flutter's button only closed the dialog). */
    fun openNfcSettings()
}

internal class AndroidNfcStatusSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NfcStatusSource {
    override fun availability(): NfcAvailability {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NfcAvailability.NOT_SUPPORTED
        return if (adapter.isEnabled) NfcAvailability.ENABLED else NfcAvailability.DISABLED
    }

    override fun openNfcSettings() {
        val intent = Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.warn(TAG, "NFC-Einstellungen nicht verfügbar", e)
            runCatching {
                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** What the reader found on a tag (scanner, spec 02 §2.1 NFC flow). */
sealed interface NfcReadResult {
    /** No NDEF technology (`verifyErrNoNdef`). */
    data object NoNdef : NfcReadResult

    /** No message or no records (`verifyErrTagEmpty`). */
    data object Empty : NfcReadResult

    /** Payload of the first record (may be empty: `verifyErrPayloadEmpty` via the processor). */
    class Payload(val bytes: ByteArray) : NfcReadResult

    /** Reading failed (`verifyErrReadError(message)`). */
    data class Failed(val message: String) : NfcReadResult
}

/** Outcome of writing the badge text record (writer, spec 02 §2.4). */
sealed interface NfcWriteResult {
    /** Written to an NDEF tag. */
    data object Written : NfcWriteResult

    /** A blank formatable tag was formatted with the message. */
    data object Formatted : NfcWriteResult

    /** Formatting failed (`writerFormatFailed`). */
    data object FormatFailed : NfcWriteResult

    /** Neither NDEF nor formatable (`writerNoNdef`). */
    data object NoNdef : NfcWriteResult

    /** Tag is not writable (`writerTagReadOnly`). */
    data object ReadOnly : NfcWriteResult

    /** Data does not fit (`writerTagTooSmall(actual, max) + writerUseNtag215`). */
    data class TooSmall(val actual: Int, val max: Int) : NfcWriteResult

    /** I/O failure; [message] includes the exception class (classified by `NfcBadgePayload.classifyWriteError`). */
    data class Failed(val message: String) : NfcWriteResult
}

/** One discovered tag. The Android implementation wraps [Tag]; tests use fakes. */
interface NfcTagHandle {
    /** Reads the first NDEF record's payload. */
    suspend fun readFirstRecordPayload(): NfcReadResult

    /**
     * Writes one NFC Forum Text record with [textPayload] (`NfcBadgePayload.encodeTextPayload`).
     * [actualSize] is checked against the tag capacity first; [onFormatting] runs before a blank
     * tag is formatted.
     */
    suspend fun writeTextRecord(textPayload: ByteArray, actualSize: Int, onFormatting: () -> Unit): NfcWriteResult
}

internal class AndroidNfcTagHandle(private val tag: Tag) : NfcTagHandle {

    override suspend fun readFirstRecordPayload(): NfcReadResult = withContext(Dispatchers.IO) {
        val ndef = Ndef.get(tag) ?: return@withContext NfcReadResult.NoNdef
        try {
            ndef.connect()
            val message = ndef.cachedNdefMessage ?: ndef.ndefMessage
            val record = message?.records?.firstOrNull() ?: return@withContext NfcReadResult.Empty
            NfcReadResult.Payload(record.payload ?: ByteArray(0))
        } catch (e: Exception) {
            NfcReadResult.Failed(e.toString())
        } finally {
            runCatching { ndef.close() }
        }
    }

    override suspend fun writeTextRecord(
        textPayload: ByteArray,
        actualSize: Int,
        onFormatting: () -> Unit,
    ): NfcWriteResult = withContext(Dispatchers.IO) {
        val message = NdefMessage(NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0), textPayload))
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            val formatable = NdefFormatable.get(tag) ?: return@withContext NfcWriteResult.NoNdef
            withContext(Dispatchers.Main) { onFormatting() }
            return@withContext try {
                formatable.connect()
                formatable.format(message)
                NfcWriteResult.Formatted
            } catch (e: Exception) {
                AppLogger.warn(TAG, "NFC-Formatierung fehlgeschlagen", e)
                NfcWriteResult.FormatFailed
            } finally {
                runCatching { formatable.close() }
            }
        }
        try {
            ndef.connect()
            if (!ndef.isWritable) return@withContext NfcWriteResult.ReadOnly
            if (actualSize > ndef.maxSize) return@withContext NfcWriteResult.TooSmall(actualSize, ndef.maxSize)
            ndef.writeNdefMessage(message)
            NfcWriteResult.Written
        } catch (e: Exception) {
            NfcWriteResult.Failed(e.toString())
        } finally {
            runCatching { ndef.close() }
        }
    }
}

/**
 * Keeps `NfcAdapter.enableReaderMode` (NFC-A/B/V = Flutter's ISO 14443 + ISO 15693) on while
 * [active] and the screen is resumed; tags are delivered on the main thread. Errors starting
 * the session go to [onError]. Only used behind `IssuanceFeatures.NFC_ENABLED`.
 */
@Composable
internal fun NfcReaderModeEffect(
    active: Boolean,
    onTag: (NfcTagHandle) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val currentOnTag = rememberUpdatedState(onTag)
    val currentOnError = rememberUpdatedState(onError)
    LifecycleResumeEffect(active) {
        val activity = context.findActivity()
        val adapter = if (active) NfcAdapter.getDefaultAdapter(context) else null
        var enabled = false
        if (active && activity != null && adapter != null) {
            val main = Handler(Looper.getMainLooper())
            try {
                adapter.enableReaderMode(
                    activity,
                    { tag -> main.post { currentOnTag.value(AndroidNfcTagHandle(tag)) } },
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_V,
                    null,
                )
                enabled = true
            } catch (e: Exception) {
                AppLogger.warn(TAG, "NFC-Reader-Modus fehlgeschlagen", e)
                currentOnError.value(e.message ?: e.toString())
            }
        } else if (active) {
            currentOnError.value(context.getString(R.string.native_nfc_adapter_unavailable))
        }
        onPauseOrDispose {
            if (enabled && activity != null) runCatching { adapter?.disableReaderMode(activity) }
        }
    }
}

private const val TAG = "NFC"
