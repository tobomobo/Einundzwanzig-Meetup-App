package space.einundzwanzig.meetup.feature.onboarding.setup

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.IoDispatcher

/** Writes the ncryptsec key file to a SAF document (spec 01 §2.3.7 `_saveKeyFile`). */
fun interface KeyFileWriter {
    /** Writes [content] as UTF-8 to the document [uri]; throws on failure. */
    suspend fun write(uri: String, content: String)
}

/** File name and content of the key file (pure, spec 01 §2.3.7). */
object KeyFile {
    /** `Nostr-Schluessel_<yyyy>-<MM>-<dd>_21MeetupApp.txt` (local date, zero-padded). */
    fun fileName(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val mm = d.monthValue.toString().padStart(2, '0')
        val dd = d.dayOfMonth.toString().padStart(2, '0')
        return "Nostr-Schluessel_${d.year}-${mm}-${dd}_21MeetupApp.txt"
    }

    /** `"<header>\n\n<wrap>\n"`. */
    fun content(header: String, wrap: String): String = "$header\n\n$wrap\n"
}

internal class AndroidKeyFileWriter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : KeyFileWriter {
    override suspend fun write(uri: String, content: String) = withContext(io) {
        val stream = context.contentResolver.openOutputStream(uri.toUri(), "wt")
            ?: throw IOException("Datei konnte nicht geöffnet werden")
        stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }
}
