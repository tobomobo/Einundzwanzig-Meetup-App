package space.einundzwanzig.meetup.data.profile.blossom

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Result of [BlossomUploadService.uploadImage] (Dart `UploadResult`).
 *
 * [error] is a raw German text (`"Datei nicht gefunden"`, `"Datei ist leer"`,
 * `"Signieren fehlgeschlagen"`, `"Antwort ohne URL"`, the server's `X-Reason`, `"HTTP <code>"`,
 * an exception text or `"Kein Server erreichbar"`); the caller shows it via
 * `evBadgeUploadFailed(error)`.
 */
data class UploadResult(val url: String?, val error: String?) {

    /** `true` when [url] is non-empty. */
    val ok: Boolean get() = !url.isNullOrEmpty()

    companion object {
        /** A successful upload. */
        fun success(url: String): UploadResult = UploadResult(url, null)

        /** A failed upload. */
        fun failure(error: String): UploadResult = UploadResult(null, error)
    }
}

/**
 * BUD-02 image upload to Blossom servers (Dart `BlossomUploadService`, spec 07 §5.5/§6.3), used
 * for event-badge images.
 *
 * The kind-24242 authorization (`t=upload`, `x=<sha256>`, `expiration=now+10 min`) is signed
 * **once** with the active signer and reused for every server (one Amber/bunker prompt), then
 * `PUT <server>/upload` is tried on [SERVERS] in order; the first success wins.
 */
interface BlossomUploadService {

    /**
     * Uploads the exact bytes of [file] (re-encode before calling; the hash covers these bytes).
     * The MIME type comes from the file extension (`jpg`/`jpeg`/`png`/`webp`/`gif`, else
     * `application/octet-stream`).
     */
    suspend fun uploadImage(file: File): UploadResult

    /** [uploadImage] for a path (Dart signature). */
    suspend fun uploadImage(localPath: String): UploadResult = uploadImage(File(localPath))

    companion object {
        /** Servers in the order they are tried. */
        val SERVERS: List<String> = listOf(
            "https://blossom.band",
            "https://blossom.primal.net",
            "https://blossom.nostr.build",
        )

        /** Per-server request timeout. */
        val TIMEOUT: Duration = 45.seconds

        /** Kind of the Blossom authorization event. */
        const val AUTH_KIND: Int = 24242

        /** Content of the authorization event (verbatim, ASCII "fuer"). */
        const val AUTH_CONTENT: String = "Bild fuer ein Einundzwanzig-Event-Badge"

        /** Lifetime of the authorization (`expiration` tag). */
        val AUTH_EXPIRATION: Duration = 10.minutes

        /** MIME type for [path] by its lower-cased extension after the last `.`. */
        fun mimeFor(path: String): String {
            val dot = path.lastIndexOf('.')
            if (dot < 0) return "application/octet-stream"
            return when (path.substring(dot + 1).lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "application/octet-stream"
            }
        }
    }
}
