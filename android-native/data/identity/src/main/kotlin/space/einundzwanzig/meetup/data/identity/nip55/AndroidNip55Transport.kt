package space.einundzwanzig.meetup.data.identity.nip55

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.log.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NIP-55 transport with the semantics of the Flutter `MainActivity` bridge (spec 01 §6.6):
 * ContentResolver (`content://com.greenart7c3.nostrsigner.<TYPE>`) first, so remembered
 * permissions sign without a popup; a foreground `nostrsigner:` intent otherwise.
 */
@Singleton
internal class AndroidNip55Transport @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val bridge: DefaultExternalSignerBridge,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : Nip55Transport {

    override fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(Nip55.AMBER_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    override suspend fun getPublicKey(): String {
        requireInstalled()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
            `package` = Nip55.AMBER_PACKAGE
            putExtra("type", "get_public_key")
            putExtra("permissions", Nip55.GET_PUBLIC_KEY_PERMISSIONS)
        }
        val data = launchForOk(intent)
        val value = data.getStringExtra("result") ?: data.getStringExtra("signature")
        if (value.isNullOrBlank()) throw Nip55Exception("cancelled", "Kein pubkey erhalten")
        return value
    }

    override suspend fun signEvent(eventJson: String, npub: String): Nip55SignResult {
        requireInstalled()
        val background = queryResolver("SIGN_EVENT", arrayOf(eventJson, "", npub)) { cursor ->
            val eventIdx = cursor.getColumnIndex("event")
            val resultIdx = cursor.getColumnIndex("result")
            val event = if (eventIdx >= 0) cursor.getString(eventIdx) else null
            val sig = if (resultIdx >= 0) cursor.getString(resultIdx) else null
            event?.let { Nip55SignResult(it, sig) }
        }
        if (background != null) return background

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$eventJson")).apply {
            `package` = Nip55.AMBER_PACKAGE
            putExtra("type", "sign_event")
            putExtra("current_user", npub)
        }
        val data = launchForOk(intent)
        val event = data.getStringExtra("event")
        val sig = data.getStringExtra("result") ?: data.getStringExtra("signature")
        if (event.isNullOrBlank()) throw Nip55Exception("cancelled", "Kein signiertes Event erhalten")
        return Nip55SignResult(event, sig)
    }

    override suspend fun nip44(type: String, peerHex: String, value: String, npub: String): String {
        requireInstalled()
        val resolverType = if (type == "nip44_encrypt") "NIP44_ENCRYPT" else "NIP44_DECRYPT"
        val background = queryResolver(resolverType, arrayOf(value, peerHex, npub)) { cursor ->
            val idx = cursor.getColumnIndex("result")
            if (idx >= 0) cursor.getString(idx) else null
        }
        if (background != null) return background

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$value")).apply {
            `package` = Nip55.AMBER_PACKAGE
            putExtra("type", type)
            putExtra("pubkey", peerHex)
            putExtra("current_user", npub)
        }
        val data = launchForOk(intent)
        val out = data.getStringExtra("result") ?: data.getStringExtra("signature")
        if (out.isNullOrBlank()) throw Nip55Exception("cancelled", "Kein Ergebnis erhalten")
        return out
    }

    private fun requireInstalled() {
        if (!isInstalled()) throw Nip55Exception("signer_missing", "Amber nicht installiert")
    }

    /**
     * Background query. A row with a non-null `rejected` column → `rejected`; [read] → result;
     * no cursor / no row / exception → `null` (fall through to the foreground intent).
     */
    private suspend fun <T> queryResolver(
        type: String,
        projection: Array<String>,
        read: (android.database.Cursor) -> T?,
    ): T? = withContext(io) {
        var rejected = false
        val value = try {
            context.contentResolver.query(
                Uri.parse("content://${Nip55.AMBER_PACKAGE}.$type"),
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val rejectedIdx = cursor.getColumnIndex("rejected")
                if (rejectedIdx >= 0 && !cursor.isNull(rejectedIdx)) {
                    rejected = true
                    null
                } else {
                    read(cursor)
                }
            }
        } catch (e: Exception) {
            AppLogger.debug("AmberSigner", "ContentResolver $type nicht verfügbar: ${e::class.simpleName}")
            null
        }
        if (rejected) throw Nip55Exception("rejected", "User hat dauerhaft abgelehnt")
        value
    }

    private suspend fun launchForOk(intent: Intent): Intent {
        val result = bridge.launch(intent)
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            throw Nip55Exception("cancelled", "Anfrage abgebrochen")
        }
        return result.data
    }
}
