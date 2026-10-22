package space.einundzwanzig.meetup.data.proofs.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.data.prefs.KeyValueEditor
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.asString
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.proofs.platform.PlatformCatalog
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofBackup
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofRepository
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateError
import space.einundzwanzig.meetup.data.proofs.platform.ProofCreateResult
import space.einundzwanzig.meetup.data.proofs.platform.VerifyStringCodec
import javax.inject.Inject

/** Port of the storage and creation half of `PlatformProofService`. */
internal class DefaultPlatformProofRepository @Inject constructor(
    private val store: KeyValueStore,
    private val identity: ProofsIdentity,
    private val time: TimeSource,
    private val log: DiagnosticLog,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : PlatformProofRepository {

    private val ownership = ProofOwnership(store, identity)

    override val proofs: Flow<List<PlatformProof>> =
        merge(store.observeAll().map { Unit }, identity.signerReady)
            .map { getSavedProofs() }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun getSavedProofs(): List<PlatformProof> = withContext(dispatcher) {
        parse(rawEntries(ownership.read(ProofOwnership.PLATFORM_OWNER)[KEY].asString))
    }

    override suspend fun createProof(
        platformId: String,
        username: String,
        customPlatformName: String?,
    ): ProofCreateResult = try {
        withContext(dispatcher) { create(platformId, username, customPlatformName) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(TAG, "Plattform-Proof fehlgeschlagen: ${e.message}")
        ProofCreateResult.Failure(ProofCreateError.SIGNER_OR_UNKNOWN, e.message ?: e.toString())
    }

    private suspend fun create(platformId: String, username: String, customPlatformName: String?): ProofCreateResult {
        val snapshot = ownership.snapshot()
        val npub = snapshot.npub
        if (npub == null || !identity.canSign()) return ProofCreateResult.Failure(ProofCreateError.NO_KEY)
        val trimmedUsername = DartString.trim(username)
        if (trimmedUsername.isEmpty()) return ProofCreateResult.Failure(ProofCreateError.EMPTY_USERNAME)

        val platform = customPlatformName?.let(PlatformCatalog::customPlatformId) ?: platformId
        val now = time.nowSeconds()
        val content = DartJson.canonicalJsonEncode(
            mapOf(
                "action" to "platform_proof",
                "protocol" to "21rep",
                "version" to 2,
                "platform" to platform,
                "username" to trimmedUsername,
                "created_at" to now,
            ),
        )
        val signed = identity.signEvent(
            kind = KIND_PLATFORM_PROOF,
            tags = listOf(
                listOf("t", "platform_proof"),
                listOf("platform", platform),
                listOf("username", trimmedUsername),
            ),
            content = content,
        )
        // External signers can block for minutes; if the identity switched meanwhile (spec 06 §14
        // R20), the proof belongs to the old key and must not land in the new key's store.
        if (identity.npub() != npub) {
            log.warn(TAG, "Identität hat während der Signatur gewechselt — Plattform-Proof verworfen")
            return ProofCreateResult.Failure(ProofCreateError.SIGNER_OR_UNKNOWN, IDENTITY_CHANGED)
        }
        val proof = PlatformProof(platform = platform, username = trimmedUsername, proofSig = signed.sig, createdAt = now)
        // Generation and its intended identity are checked in the write transaction (R20).
        val saved = ownership.edit(snapshot) {
            val kept = ownedEntries(snapshot).filterNot { platformOf(it) == platform }
            write(kept + proof.toStorageJson())
            snapshot.stamp(this, ProofOwnership.PLATFORM_OWNER)
        }
        if (!saved) throw IllegalStateException("Plattform-Proof konnte nicht gespeichert werden")
        return ProofCreateResult.Success(
            verifyString = VerifyStringCodec.build(npub, platform, trimmedUsername, signed.sig),
            proof = proof,
        )
    }

    override suspend fun removeProof(platformId: String): Boolean = withContext(dispatcher) {
        val snapshot = ownership.snapshot()
        val ok = ownership.edit(snapshot) {
            write(ownedEntries(snapshot).filterNot { platformOf(it) == platformId })
            snapshot.stamp(this, ProofOwnership.PLATFORM_OWNER)
        }
        if (!ok) log.warn(TAG, "Plattform-Proof konnte nicht gelöscht werden")
        ok
    }

    override suspend fun getProofsForPublishing(): LinkedHashMap<String, PlatformProof> =
        LinkedHashMap<String, PlatformProof>().apply { getSavedProofs().forEach { put(it.platform, it) } }

    override suspend fun replaceAllRaw(validated: List<JsonElement>): Boolean = withContext(dispatcher) {
        val snapshot = ownership.snapshot()
        // Enforced here, not only by the caller (Audit 2 finding #4); idempotent.
        val restorable = PlatformProofBackup.restorable(validated)
        if (restorable.isEmpty()) true else ownership.edit(snapshot) {
            write(restorable)
            snapshot.stamp(this, ProofOwnership.PLATFORM_OWNER)
        }
    }

    override suspend fun unionRaw(incoming: List<JsonElement>): Boolean = withContext(dispatcher) {
        val snapshot = ownership.snapshot()
        val restorable = PlatformProofBackup.restorable(incoming)
        var unreadable = false
        val ok = ownership.edit(snapshot) {
            unreadable = false
            val stored = if (snapshot.owns(this, ProofOwnership.PLATFORM_OWNER)) get(KEY) else null
            val raw = stored.asString
            val device: List<JsonElement> = when {
                stored == null || raw == "" -> emptyList()
                raw == null -> null
                else -> try {
                    DartJson.decode(raw) as? JsonArray
                } catch (_: Exception) {
                    null
                }
            } ?: run {
                unreadable = true
                return@edit // keep the device value (Dart threw before writing)
            }
            val merged = PlatformProofBackup.union(device, restorable)
            if (merged.isNotEmpty()) {
                write(merged)
                snapshot.stamp(this, ProofOwnership.PLATFORM_OWNER)
            }
        }
        if (unreadable) {
            log.warn(TAG, "platform_proofs nicht lesbar — Backup-Proofs nicht zusammengeführt, Gerätewert behalten")
            false
        } else {
            ok
        }
    }

    override suspend fun clear(): Boolean {
        val snapshot = ownership.snapshot()
        return ownership.edit(snapshot) {
            remove(KEY)
            remove(ProofOwnership.PLATFORM_OWNER)
        }
    }

    private fun KeyValueEditor.ownedEntries(snapshot: ProofOwnership.Snapshot): List<JsonElement> =
        if (snapshot.owns(this, ProofOwnership.PLATFORM_OWNER)) rawEntries(getString(KEY)) else emptyList()

    private fun KeyValueEditor.write(entries: List<JsonElement>) {
        setString(KEY, DartJson.encode(JsonArray(entries)))
    }

    private fun rawEntries(json: String?): List<JsonElement> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            DartJson.decode(json) as? JsonArray ?: run {
                log.warn(TAG, "platform_proofs ist keine Liste — ignoriert")
                emptyList()
            }
        } catch (e: Exception) {
            log.warn(TAG, "platform_proofs nicht lesbar: ${e.message}")
            emptyList()
        }
    }

    private fun parse(entries: List<JsonElement>): List<PlatformProof> {
        val parsed = entries.mapNotNull(PlatformProof::fromStorageJson)
        if (parsed.size != entries.size) {
            log.debug(TAG, "${entries.size - parsed.size} von ${entries.size} Plattform-Proofs unlesbar — übersprungen")
        }
        return parsed
    }

    /** Only string platforms match, the same rule as [PlatformProof.fromStorageJson]. */
    private fun platformOf(entry: JsonElement): String? = (entry as? JsonObject)?.get("platform").dartStringOrNull()

    companion object {
        private const val TAG = "PlatformProof"
        private const val KEY = PlatformProof.STORAGE_KEY
        private const val IDENTITY_CHANGED = "Die Identität hat während der Signatur gewechselt."

        /** Kind of the never-published platform proof event. */
        const val KIND_PLATFORM_PROOF: Int = 21003
    }
}
