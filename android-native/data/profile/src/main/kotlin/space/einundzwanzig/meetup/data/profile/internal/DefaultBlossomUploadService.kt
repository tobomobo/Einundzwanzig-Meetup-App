package space.einundzwanzig.meetup.data.profile.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.crypto.DartBase64
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.core.crypto.toHex
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.core.network.executeCapped
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.profile.blossom.BlossomUploadService
import space.einundzwanzig.meetup.data.profile.blossom.BlossomUploadService.Companion.AUTH_CONTENT
import space.einundzwanzig.meetup.data.profile.blossom.BlossomUploadService.Companion.AUTH_EXPIRATION
import space.einundzwanzig.meetup.data.profile.blossom.BlossomUploadService.Companion.AUTH_KIND
import space.einundzwanzig.meetup.data.profile.blossom.BlossomUploadService.Companion.TIMEOUT
import space.einundzwanzig.meetup.data.profile.blossom.UploadResult
import space.einundzwanzig.meetup.data.identity.SigningService
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Signs an event with the active signer (`SigningService.signEvent`). */
internal fun interface EventSigner {
    suspend fun sign(kind: Int, tags: List<List<String>>, content: String): NostrEvent
}

/** Port of `BlossomUploadService.uploadImage` (BUD-02, spec 07 §5.5/§6.3). */
internal class DefaultBlossomUploadService(
    private val client: OkHttpClient,
    private val signer: EventSigner,
    private val time: TimeSource,
    private val ioDispatcher: CoroutineDispatcher,
    private val servers: List<String> = BlossomUploadService.SERVERS,
    private val log: DiagnosticLog = AppLogger,
) : BlossomUploadService {

    /** Hilt entry point: signs with the active signer of [SigningService]. */
    @Inject
    constructor(
        client: OkHttpClient,
        signing: SigningService,
        time: TimeSource,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        log: DiagnosticLog,
    ) : this(client, EventSigner { k, t, c -> signing.signEvent(k, t, c) }, time, ioDispatcher, BlossomUploadService.SERVERS, log)

    override suspend fun uploadImage(file: File): UploadResult {
        // Dart `File.exists()` is false for directories; an unreadable file is "not found" too
        // instead of an exception escaping the UploadResult contract. Hashing stays off the caller.
        val (bytes, digest) = withContext(ioDispatcher) {
            val read = if (!file.isFile) {
                null
            } else {
                try {
                    file.readBytes()
                } catch (_: IOException) {
                    null
                }
            }
            read?.let { it to Sha256.digest(it).toHex() }
        } ?: return UploadResult.failure("Datei nicht gefunden")
        if (bytes.isEmpty()) return UploadResult.failure("Datei ist leer")

        val mime = BlossomUploadService.mimeFor(file.path)
        log.debug(TAG, "Upload vorbereitet: ${bytes.size} Bytes, $mime, sha256 ${digest.take(12)}…")

        // Signed ONCE and reused for every server: Amber / a remote signer prompts only once.
        val authHeader = try {
            val expiration = (time.nowMillis() + AUTH_EXPIRATION.inWholeMilliseconds) / 1000
            val signed = signer.sign(
                AUTH_KIND,
                listOf(listOf("t", "upload"), listOf("x", digest), listOf("expiration", expiration.toString())),
                AUTH_CONTENT,
            )
            authHeader(signed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Anmeldung konnte nicht signiert werden", e)
            return UploadResult.failure("Signieren fehlgeschlagen")
        }

        var lastError: String? = null
        for (server in servers) {
            val request = try {
                Request.Builder()
                    .url("$server/upload")
                    .header("Authorization", authHeader)
                    .put(bytes.toRequestBody(mime.toMediaType()))
                    .build()
            } catch (e: IllegalArgumentException) {
                lastError = e.toString()
                continue
            }
            when (val response = client.executeCapped(request, MAX_RESPONSE_BYTES, TIMEOUT)) {
                is HttpResult.Response -> {
                    if (response.status == 200 || response.status == 201) {
                        try {
                            val json = DartJson.decode(response.text()) as? JsonObject
                                ?: throw IllegalArgumentException("Antwort ist kein JSON-Objekt")
                            val url = json["url"].let {
                                when (it) {
                                    null, is JsonNull -> ""
                                    is JsonPrimitive -> it.content
                                    else -> it.toString()
                                }
                            }
                            if (url.isNotEmpty()) {
                                log.debug(TAG, "Upload bei $server erfolgreich: $url")
                                return UploadResult.success(url)
                            }
                            lastError = "Antwort ohne URL"
                        } catch (e: Exception) {
                            lastError = e.toString()
                        }
                    } else {
                        // Blossom puts the reason into the X-Reason header.
                        lastError = response.header("x-reason") ?: "HTTP ${response.status}"
                    }
                    log.debug(TAG, "$server abgelehnt: $lastError")
                }
                is HttpResult.TooLarge -> {
                    lastError = "HTTP ${response.status}"
                    log.debug(TAG, "$server abgelehnt: Antwort zu groß")
                }
                is HttpResult.Failure -> {
                    lastError = response.cause.toString()
                    log.debug(TAG, "$server nicht erreichbar: ${response.cause}")
                }
            }
        }
        return UploadResult.failure(lastError ?: "Kein Server erreichbar")
    }

    companion object {
        const val TAG: String = "Blossom"

        /** Blossom answers with a small blob descriptor; anything bigger is not a valid answer. */
        private const val MAX_RESPONSE_BYTES: Long = 256L * 1024

        /** `Nostr <base64(utf8(jsonEncode(event)))>`, standard alphabet with padding. */
        internal fun authHeader(event: NostrEvent): String = "Nostr " + DartBase64.encode(DartUtf8.encode(event.toJson()))
    }
}
