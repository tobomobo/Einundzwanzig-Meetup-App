package space.einundzwanzig.meetup.feature.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.data.backup.BackupFiles
import java.io.IOException
import javax.inject.Inject

/** Reads and writes SAF documents by URI string (fakeable in tests). */
interface BackupDocuments {
    /** Writes [content] to the document [uri] (from `CreateDocument`). */
    suspend fun write(uri: String, content: String)

    /**
     * Reads the document [uri] (from `OpenDocument`) as strict UTF-8.
     *
     * @throws space.einundzwanzig.meetup.data.backup.BackupReadException on any read error.
     */
    suspend fun read(uri: String): String
}

internal class ContentResolverBackupDocuments @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BackupDocuments {

    override suspend fun write(uri: String, content: String) {
        val output = withContext(io) {
            context.contentResolver.openOutputStream(Uri.parse(uri), "w")
        } ?: throw IOException("Kein Ausgabestrom für $uri")
        BackupFiles.write(output, content, io)
    }

    override suspend fun read(uri: String): String =
        BackupFiles.read({ context.contentResolver.openInputStream(Uri.parse(uri)) }, io)
}
