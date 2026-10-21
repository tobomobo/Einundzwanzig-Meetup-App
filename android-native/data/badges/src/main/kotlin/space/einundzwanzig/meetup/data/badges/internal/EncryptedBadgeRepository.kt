package space.einundzwanzig.meetup.data.badges.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.dartToInt
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartNumOrNull
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.crypto.AesGcmBlob
import space.einundzwanzig.meetup.core.crypto.CryptoRandom
import space.einundzwanzig.meetup.core.crypto.Hex
import space.einundzwanzig.meetup.core.crypto.toHex
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.secure.SecureRead
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.FormerCollection
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.badges.store.BadgeStoreKeys
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Port of the badge store of `lib/models/badge.dart` (spec 02 §4.2), algorithm for algorithm.
 * Dart's `try { secure.read } catch` maps to [SecureStore.readChecked] → [SecureRead.Unreadable];
 * Dart's throwing `write`/`delete` map to their `false` results.
 */
@Singleton
internal class EncryptedBadgeRepository @Inject constructor(
    private val prefs: KeyValueStore,
    private val secure: SecureStore,
    private val timeSource: TimeSource,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : BadgeRepository {

    private val mutex = Mutex()
    private val _badges = MutableStateFlow<List<MeetupBadge>>(emptyList())
    private val _unreadable = MutableStateFlow(false)
    private val _loaded = MutableStateFlow(false)
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val badges: StateFlow<List<MeetupBadge>> = _badges.asStateFlow()
    override val badgesUnreadable: StateFlow<Boolean> = _unreadable.asStateFlow()
    override val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    override val savedEvents: SharedFlow<Unit> = _saved.asSharedFlow()

    private var unreadable: Boolean
        get() = _unreadable.value
        set(value) {
            _unreadable.value = value
        }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(dispatcher) { mutex.withLock { block() } }

    /**
     * Writers must know whether the file is readable before they touch it: without a load in this
     * process [unreadable] is still `false`, and a save would encrypt with whatever key sits in
     * [BadgeStoreKeys.KEY] and overwrite a file only the `_BACKUP` (or another) key opens. Dart
     * relied on `main()` loading first; here every write path loads once on its own.
     */
    private suspend fun ensureLoaded() {
        if (!_loaded.value) _badges.value = loadInternal()
    }

    // ------------------------------------------------------------------ public API

    override suspend fun loadBadges(): List<MeetupBadge> = locked {
        loadInternal().also { _badges.value = it }
    }

    override suspend fun saveBadges(badges: List<MeetupBadge>, replaceUnreadable: Boolean, discardLocked: Boolean): Boolean =
        locked {
            ensureLoaded()
            saveInternal(badges, replaceUnreadable, discardLocked)
        }

    override suspend fun update(transform: (current: List<MeetupBadge>) -> List<MeetupBadge>?): Boolean = locked {
        // Every write goes through this singleton under the mutex, so after the first load the
        // in-memory state is the stored collection (kept-aside badges included).
        ensureLoaded()
        if (unreadable) return@locked false
        val current = _badges.value
        val next = transform(current) ?: return@locked true
        if (next === current) return@locked true
        saveInternal(next)
    }

    override suspend fun keepAside(badge: MeetupBadge): Boolean = locked {
        ensureLoaded()
        val pending = readPending()
        if (pending == null) {
            AppLogger.error(TAG, "Badge nicht vorgemerkt, Vormerkung nicht lesbar")
            return@locked false
        }
        if (pending.any { it.id == badge.id }) return@locked true
        if (!writePending(pending + badge)) {
            AppLogger.error(TAG, "Badge nicht vorgemerkt, kein Schlüssel verfügbar")
            return@locked false
        }
        AppLogger.warn(TAG, "Badge vorgemerkt, Sammlung ist gerade nicht lesbar")
        true
    }

    override suspend fun stageIncoming(badges: List<MeetupBadge>): Boolean = locked {
        if (badges.isEmpty()) return@locked false
        ensureLoaded()
        val key = getBadgeKey(create = true) ?: return@locked false
        try {
            commitString(BadgeStoreKeys.INCOMING, encryptBlob(badges, key))
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Puffer für den Identitätswechsel nicht geschrieben", e)
            false
        }
    }

    override suspend fun promoteIncoming(formerNpub: String): Boolean = locked {
        val incoming = prefs.getString(BadgeStoreKeys.INCOMING)
        if (incoming == null || !incoming.contains(':')) return@locked false
        ensureLoaded()
        // A crash between primary and staging cleanup: the promotion already happened.
        if (prefs.getString(BadgeStoreKeys.PRIMARY) != incoming) {
            val retained = retainCurrent(REASON_REPLACED, npub = formerNpub)
            if (!retained.ok) return@locked false
            if (!deleteSecureCopy()) return@locked false
            prefs.remove(BadgeStoreKeys.PRESERVED)
            prefs.remove(BadgeStoreKeys.OLDER)
            releasePending(keepKey = retained.keepPendingKey)
            if (!commitString(BadgeStoreKeys.PRIMARY, incoming)) return@locked false
        }
        if (prefs.getString(BadgeStoreKeys.PRIMARY) != incoming) return@locked false
        writeSecureCopy(incoming)
        prefs.remove(BadgeStoreKeys.INCOMING)
        unreadable = false
        openWithDeviceKeys(incoming)?.let { _badges.value = it }
        _saved.tryEmit(Unit)
        true
    }

    override suspend fun discardIncoming() {
        locked { prefs.remove(BadgeStoreKeys.INCOMING) }
    }

    override suspend fun formerCollections(): List<FormerCollection> = locked {
        val entries = loadFormer()
        val out = ArrayList<FormerCollection>(entries.size)
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            out += FormerCollection(
                index = i,
                at = DartDateTime.fromMillisecondsSinceEpoch(atOf(e) * 1000),
                reason = stringOf(e["reason"]),
                npub = stringOf(e["npub"]),
                badges = openWithDeviceKeys(stringOf(e["blob"])),
            )
        }
        out
    }

    override suspend fun mergeFormer(index: Int): Boolean = locked {
        val entries = loadFormer()
        if (index < 0 || index >= entries.size) return@locked false
        val former = openWithDeviceKeys(stringOf(entries[index]["blob"])) ?: return@locked false
        val current = loadInternal()
        _badges.value = current
        if (unreadable) return@locked false
        saveInternal(BadgeIdentity.unionByIdentity(current, former))
    }

    override suspend fun resetAll() {
        locked {
            if (!secure.write(BadgeStoreKeys.RESET_PENDING, "1")) AppLogger.warn(TAG, "Reset-Marker nicht geschrieben")
            for (key in listOf(
                BadgeStoreKeys.PRIMARY, BadgeStoreKeys.PRESERVED, BadgeStoreKeys.OLDER,
                BadgeStoreKeys.LEGACY, BadgeStoreKeys.INCOMING, BadgeStoreKeys.FORMER,
            )) {
                prefs.remove(key)
            }
            writePending(emptyList())
            deleteSecureCopy()
            deleteFormer()
            if (resetStillPending()) AppLogger.warn(TAG, "Reset unvollständig, die Zweitkopie bleibt gesperrt")
            unreadable = false
            _badges.value = emptyList()
            _loaded.value = true
        }
    }

    // ------------------------------------------------------------------ load / save

    private suspend fun loadInternal(): List<MeetupBadge> {
        // The first identityKey() (wallet filter, union) must not read the case table on the main thread.
        DartString.warmUp()
        val result = loadFromStores()
        _loaded.value = true
        return result
    }

    private suspend fun loadFromStores(): List<MeetupBadge> {
        val blob = prefs.getString(BadgeStoreKeys.PRIMARY)
        val preserved = prefs.getString(BadgeStoreKeys.PRESERVED)
        val older = prefs.getString(BadgeStoreKeys.OLDER)
        val blobs = ArrayList<String>()
        fun addBlob(raw: String?) {
            if (raw != null && raw.contains(':') && raw !in blobs) blobs += raw
        }
        addBlob(blob)
        // Right after the primary: if it is gone (iOS reinstall), the second copy becomes the
        // new primary. After an incomplete reset it stays out.
        val resetPending = resetStillPending()
        val secureCopy = if (resetPending) null else readSecureCopy()
        addBlob(secureCopy)
        addBlob(preserved)
        addBlob(older)

        if (blobs.isNotEmpty()) {
            val opened = openAll(blob, blobs)
            if (opened != null) {
                if (opened.primary == null) {
                    // Park the unreadable primary first; if that fails it stays primary.
                    var parked = true
                    if (blob != null && blob.contains(':') && blob != opened.blob) {
                        if (preserved == opened.blob) {
                            // Archive the unreadable file first, then promote the readable one:
                            // the other order could overwrite the only readable version.
                            parked = appendFormer(listOf(formerEntry(REASON_ROTATED, "", blob)))
                            if (parked) parked = commitString(BadgeStoreKeys.PRIMARY, opened.blob)
                            if (parked) commitString(BadgeStoreKeys.PRESERVED, blob)
                        } else {
                            parked = stashBlob(blob)
                        }
                    }
                    if (parked) prefs.setString(BadgeStoreKeys.PRIMARY, opened.blob)
                } else if (!resetPending && blob != null && blob.contains(':') && secureCopy.isNullOrEmpty()) {
                    // Existing install: the second copy is created on open, not only on the next scan.
                    writeSecureCopy(blob)
                }
                rememberKey(opened.key)
                // Every version that opens belongs to the collection: the union goes into the primary.
                if (opened.openedCount > 1 && !BadgeIdentity.sameBadges(opened.anchor, opened.badges)) {
                    unreadable = false
                    val pending = readPending()
                    if (saveInternal(opened.badges)) {
                        return if (pending.isNullOrEmpty()) opened.badges else BadgeIdentity.unionByIdentity(opened.badges, pending)
                    }
                }
                return finishReadable(opened.badges)
            }
            // Nothing opened. Only fill, never displace.
            if (preserved.isNullOrEmpty() && blob != null && blob.contains(':')) {
                prefs.setString(BadgeStoreKeys.PRESERVED, blob)
            }
            unreadable = true
            AppLogger.warn(TAG, "Badge-Datei nicht entschlüsselbar, sie bleibt unverändert liegen")
            return emptyList()
        }

        // Plaintext only if no encrypted file exists yet.
        val legacyJson = prefs.getStringList(BadgeStoreKeys.LEGACY)
        if (!legacyJson.isNullOrEmpty()) {
            AppLogger.debug(TAG, "Migriere ${legacyJson.size} Badges von Klartext zu verschlüsselt")
            val legacy = parseEach(legacyJson)
            if (legacy.isNotEmpty()) {
                unreadable = false
                if (saveInternal(legacy)) return legacy
            }
        }
        unreadable = false
        return emptyList()
    }

    private suspend fun finishReadable(badges: List<MeetupBadge>): List<MeetupBadge> {
        unreadable = false
        val pending = readPending()
        if (pending.isNullOrEmpty()) return badges
        val merged = BadgeIdentity.unionByIdentity(badges, pending)
        // saveInternal takes the kept-aside badges along and clears them (and their key) only
        // after writing. If it fails they stay where they are.
        return if (saveInternal(merged)) merged else badges
    }

    private suspend fun saveInternal(
        badges: List<MeetupBadge>,
        replaceUnreadable: Boolean = false,
        discardLocked: Boolean = false,
    ): Boolean {
        if (badges.isEmpty()) {
            // Clearing archives, it never deletes. Only an explicit reset clears an unreadable file.
            if (unreadable && !discardLocked) return false
            val retained = retainCurrent(REASON_CLEARED)
            if (!retained.ok) return false
            // The second copy first: if prefs were empty and it stayed, the next load would
            // silently bring the old collection back.
            if (!deleteSecureCopy()) return false
            prefs.remove(BadgeStoreKeys.PRIMARY)
            prefs.remove(BadgeStoreKeys.PRESERVED)
            prefs.remove(BadgeStoreKeys.OLDER)
            prefs.remove(BadgeStoreKeys.LEGACY)
            releasePending(keepKey = retained.keepPendingKey)
            unreadable = false
            _badges.value = emptyList()
            _saved.tryEmit(Unit)
            return true
        }

        if (unreadable && !replaceUnreadable) return false

        val currentBlob = prefs.getString(BadgeStoreKeys.PRIMARY)
        val hasBlob = currentBlob != null && currentBlob.contains(':')
        if (replaceUnreadable && hasBlob && !stashBlob(currentBlob!!)) return false

        // Without a key a new one is created only when there is no file, or on a deliberate replace.
        val key = getBadgeKey(create = !hasBlob || replaceUnreadable)
        if (key == null) {
            AppLogger.warn(TAG, "Speichern ausgesetzt, Schlüssel nicht verfügbar")
            return false
        }

        val pending = readPending() // null = present but unreadable: keep it for later
        val toStore = BadgeIdentity.unionByIdentity(badges, pending ?: emptyList())
        try {
            val stored = encryptBlob(toStore, key)
            if (!commitString(BadgeStoreKeys.PRIMARY, stored)) return false
            writeSecureCopy(stored)
            if (prefs.contains(BadgeStoreKeys.LEGACY)) {
                prefs.remove(BadgeStoreKeys.LEGACY)
                AppLogger.debug(TAG, "Legacy-Klartextbadges aus SharedPrefs entfernt")
            }
        } catch (e: Exception) {
            // Dart fell back to a plaintext `badges` list here when no file existed; the native
            // app never writes plaintext (spec 02 §14 item 15).
            AppLogger.error(TAG, "Verschlüsselung fehlgeschlagen, nichts gespeichert", e)
            return false
        }

        if (pending != null) writePending(emptyList())
        unreadable = false
        _badges.value = toStore
        _saved.tryEmit(Unit)
        return true
    }

    // ------------------------------------------------------------------ keys

    /**
     * Reads the badge key. A new one is created only with [create] and only if nothing usable is
     * stored. A read error never overwrites; an unknown value is moved to `_suspect` on create.
     */
    private suspend fun getBadgeKey(create: Boolean): ByteArray? {
        val existing = when (val r = secure.readChecked(BadgeStoreKeys.KEY)) {
            is SecureRead.Present -> r.value
            SecureRead.Absent -> null
            is SecureRead.Unreadable -> {
                AppLogger.warn(TAG, "Badge-Schlüssel nicht lesbar (${r.reason})")
                return null
            }
        }
        keyFromHex(existing)?.let { return it }
        if (existing != null && DartString.trim(existing).isNotEmpty()) {
            val length = DartString.trim(existing).length
            if (!create) {
                AppLogger.warn(TAG, "Badge-Schlüssel hat unerwartetes Format ($length Zeichen), er bleibt liegen")
                return null
            }
            if (!secure.write(BadgeStoreKeys.KEY_SUSPECT, existing)) {
                AppLogger.warn(TAG, "Verdächtiger Badge-Schlüssel nicht beiseite zu schieben")
                return null
            }
            AppLogger.warn(
                TAG,
                "Badge-Schlüssel unerwartetes Format ($length Zeichen), beiseitegeschoben unter ${BadgeStoreKeys.KEY_SUSPECT}",
            )
        }
        if (!create) return null
        val key = CryptoRandom.bytes(KEY_BYTES)
        if (!secure.write(BadgeStoreKeys.KEY, key.toHex())) {
            AppLogger.warn(TAG, "Neuer Badge-Schlüssel nicht schreibbar")
            return null
        }
        AppLogger.debug(TAG, "Neuer Badge-Encryption-Key generiert")
        return key
    }

    private suspend fun rememberKey(key: ByteArray) {
        val hex = key.toHex()
        when (val r = secure.readChecked(BadgeStoreKeys.KEY)) {
            is SecureRead.Unreadable -> {
                AppLogger.warn(TAG, "Funktionierender Schlüssel nicht zurückgeschrieben (${r.reason})")
                return
            }
            is SecureRead.Present -> if (DartString.lower(DartString.trim(r.value)) == hex) return
            SecureRead.Absent -> Unit
        }
        if (!secure.write(BadgeStoreKeys.KEY, hex)) AppLogger.warn(TAG, "Funktionierender Schlüssel nicht zurückgeschrieben")
    }

    /**
     * [primary], the `_BACKUP` migration copy, then every 64-hex value in the secure store
     * (deduplicated) except the identity secrets (spec 02 §14 risk 14): the Nostr private key and
     * the NIP-46 session key, including their `_BACKUP` and `:data:migration` recovery copies,
     * are never tried as AES keys.
     */
    private suspend fun candidateKeys(primary: ByteArray?): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val seen = HashSet<String>()
        fun add(raw: String?) {
            val key = keyFromHex(raw) ?: return
            if (seen.add(key.toHex())) out += key
        }
        if (primary != null) add(primary.toHex())
        add((secure.readChecked(BadgeStoreKeys.KEY_BACKUP) as? SecureRead.Present)?.value)
        secure.readAllChecked()
            .onSuccess { all -> all.forEach { (name, value) -> if (!isIdentitySecret(name)) add(value) } }
            .onFailure { AppLogger.debug(TAG, "Schlüsselsuche unvollständig: ${it::class.simpleName}") }
        return out
    }

    /** `true` for a secure entry that holds (a copy of) an identity secret, never a badge key. */
    private fun isIdentitySecret(name: String): Boolean {
        var base = name
        for (prefix in IdentityKeys.RECOVERY_PREFIXES) {
            if (base.startsWith("$prefix/")) {
                base = base.substring(prefix.length + 1)
                break
            }
        }
        base = base.removeSuffix(BACKUP_SUFFIX)
        return base in IdentityKeys.IDENTITY_SECRET_NAMES
    }

    /** Own key for kept-aside scans, independent of the badge key; deleted together with them. */
    private suspend fun getPendingKey(create: Boolean): ByteArray? {
        val raw = when (val r = secure.readChecked(BadgeStoreKeys.PENDING_KEY)) {
            is SecureRead.Present -> r.value
            SecureRead.Absent -> null
            is SecureRead.Unreadable -> {
                AppLogger.warn(TAG, "Schlüssel für vorgemerkte Badges nicht verfügbar (${r.reason})")
                return null
            }
        }
        val parsed = keyFromHex(raw)
        if (parsed != null || !create) return parsed
        if (raw != null && DartString.trim(raw).isNotEmpty()) {
            if (!secure.write(BadgeStoreKeys.PENDING_KEY_SUSPECT, raw)) return null
        }
        val key = CryptoRandom.bytes(KEY_BYTES)
        if (!secure.write(BadgeStoreKeys.PENDING_KEY, key.toHex())) {
            AppLogger.warn(TAG, "Schlüssel für vorgemerkte Badges nicht verfügbar")
            return null
        }
        return key
    }

    // ------------------------------------------------------------------ blobs

    private class Opened(
        val badges: List<MeetupBadge>,
        val anchor: List<MeetupBadge>,
        val openedCount: Int,
        val primary: String?,
        val blob: String,
        val key: ByteArray,
    )

    /**
     * Opens every version and unites per event. [Opened.anchor] is the content of the version
     * that is (or becomes) the primary file.
     */
    private suspend fun openAll(primaryBlob: String?, blobs: List<String>): Opened? {
        val keys = candidateKeys(getBadgeKey(create = false))
        var openedPrimary: String? = null
        var openedPrimaryKey: ByteArray? = null
        var firstBlob: String? = null
        var firstKey: ByteArray? = null
        var anchor: List<MeetupBadge> = emptyList()
        var openedCount = 0
        val allRead = ArrayList<MeetupBadge>()
        for (blob in blobs) {
            for (key in keys) {
                val badges = decryptBlob(blob, key) ?: continue
                openedCount++
                if (firstBlob == null) {
                    firstBlob = blob
                    firstKey = key
                    anchor = badges
                }
                if (blob == primaryBlob) {
                    openedPrimary = blob
                    openedPrimaryKey = key
                    anchor = badges
                }
                allRead += badges
                break
            }
        }
        if (firstBlob == null) return null
        return Opened(
            badges = BadgeIdentity.unionByIdentity(allRead, emptyList()),
            anchor = anchor,
            openedCount = openedCount,
            primary = openedPrimary,
            blob = openedPrimary ?: firstBlob,
            key = openedPrimaryKey ?: firstKey!!,
        )
    }

    /** Dart `_openFormer`: the first candidate key that opens [blob], else `null`. */
    private suspend fun openWithDeviceKeys(blob: String): List<MeetupBadge>? {
        for (key in candidateKeys(getBadgeKey(create = false))) {
            decryptBlob(blob, key)?.let { return it }
        }
        return null
    }

    /** `false` if the oldest backup could not be archived; then the chain stays as it is. */
    private suspend fun stashBlob(blob: String): Boolean {
        val preserved = prefs.getString(BadgeStoreKeys.PRESERVED)
        if (!preserved.isNullOrEmpty()) {
            if (preserved == blob) return true
            val older = prefs.getString(BadgeStoreKeys.OLDER)
            if (!appendFormer(listOf(formerEntry(REASON_ROTATED, "", older ?: "")))) return false
            if (!commitString(BadgeStoreKeys.OLDER, preserved)) return false
        }
        return commitString(BadgeStoreKeys.PRESERVED, blob)
    }

    private suspend fun writeSecureCopy(stored: String): Boolean {
        val ok = secure.write(BadgeStoreKeys.PRIMARY, stored)
        if (!ok) AppLogger.warn(TAG, "Zweitkopie im sicheren Speicher nicht geschrieben")
        return ok
    }

    private suspend fun readSecureCopy(): String? = when (val r = secure.readChecked(BadgeStoreKeys.PRIMARY)) {
        is SecureRead.Present -> r.value
        SecureRead.Absent -> null
        is SecureRead.Unreadable -> {
            AppLogger.warn(TAG, "Zweitkopie im sicheren Speicher nicht lesbar (${r.reason})")
            null
        }
    }

    private suspend fun deleteSecureCopy(): Boolean = deleteSecureKey(BadgeStoreKeys.PRIMARY)

    /** Deleted and verified gone (`null`/empty on re-read). */
    private suspend fun deleteSecureKey(key: String): Boolean {
        if (!secure.delete(key)) {
            AppLogger.warn(TAG, "Sicherer Speicher nicht geleert ($key)")
            return false
        }
        return when (val r = secure.readChecked(key)) {
            SecureRead.Absent -> true
            is SecureRead.Present -> r.value.isEmpty()
            is SecureRead.Unreadable -> {
                AppLogger.warn(TAG, "Sicherer Speicher nicht geleert ($key): ${r.reason}")
                false
            }
        }
    }

    /**
     * `true` while a second copy survived a reset: load ignores it and every call retries the
     * deletion; once both copies are gone the marker is removed.
     */
    private suspend fun resetStillPending(): Boolean {
        val marker = (secure.readChecked(BadgeStoreKeys.RESET_PENDING) as? SecureRead.Present)?.value
        if (marker != "1") return false
        val badgeGone = deleteSecureKey(BadgeStoreKeys.PRIMARY)
        val formerGone = deleteSecureKey(BadgeStoreKeys.FORMER)
        if (badgeGone && formerGone) {
            deleteSecureKey(BadgeStoreKeys.RESET_PENDING)
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ pending (kept aside)

    /** `null`: a kept-aside blob exists but cannot be opened right now; never overwrite or clear it then. */
    private suspend fun readPending(): List<MeetupBadge>? {
        val out = ArrayList<MeetupBadge>()
        val blob = prefs.getString(BadgeStoreKeys.PENDING)
        if (!blob.isNullOrEmpty()) {
            val key = getPendingKey(create = false)
            val badges = key?.let { decryptBlob(blob, it) }
            if (badges == null) {
                AppLogger.warn(TAG, "Vorgemerkte Badges gerade nicht lesbar")
                return null
            }
            out += badges
        }
        out += parseEach(prefs.getStringList(BadgeStoreKeys.LEGACY_PENDING).orEmpty())
        return out
    }

    /** `false`: nothing written; there is no plaintext fallback. */
    private suspend fun writePending(badges: List<MeetupBadge>): Boolean {
        if (badges.isEmpty()) {
            prefs.remove(BadgeStoreKeys.LEGACY_PENDING)
            if (!prefs.contains(BadgeStoreKeys.PENDING)) return true
            prefs.remove(BadgeStoreKeys.PENDING)
            secure.delete(BadgeStoreKeys.PENDING_KEY)
            return true
        }
        val key = getPendingKey(create = true) ?: return false
        val written = try {
            prefs.setString(BadgeStoreKeys.PENDING, encryptBlob(badges, key))
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Vormerkung nicht verschlüsselbar", e)
            false
        }
        if (!written) return false
        prefs.remove(BadgeStoreKeys.LEGACY_PENDING)
        return true
    }

    private suspend fun releasePending(keepKey: Boolean) {
        prefs.remove(BadgeStoreKeys.LEGACY_PENDING)
        prefs.remove(BadgeStoreKeys.PENDING)
        if (keepKey) return
        if (!secure.delete(BadgeStoreKeys.PENDING_KEY)) AppLogger.warn(TAG, "Schlüssel der Vormerkung nicht gelöscht")
    }

    // ------------------------------------------------------------------ archive

    private data class Retained(val ok: Boolean, val keepPendingKey: Boolean)

    /**
     * One archive version for everything that opens (united, under the device key) plus every
     * blob that does not open (raw, it keeps its chance for a later key). `keepPendingKey`: the
     * kept-aside blob did not open, so its key must stay or the archived blob would be mute.
     */
    private suspend fun retainCurrent(reason: String, npub: String = ""): Retained {
        val blobs = ArrayList<String>()
        fun add(raw: String?) {
            if (raw != null && raw.contains(':') && raw !in blobs) blobs += raw
        }
        add(prefs.getString(BadgeStoreKeys.PRIMARY))
        if (!resetStillPending()) add(readSecureCopy())
        add(prefs.getString(BadgeStoreKeys.PRESERVED))
        add(prefs.getString(BadgeStoreKeys.OLDER))
        val pendingBlob = prefs.getString(BadgeStoreKeys.PENDING)
        add(pendingBlob)
        val legacy = legacyBadges()
        if (blobs.isEmpty() && legacy.isEmpty()) return Retained(ok = true, keepPendingKey = false)

        val deviceKey = getBadgeKey(create = false)
        // Plaintext from old installs belongs into the same version; without a key: delete nothing.
        if (legacy.isNotEmpty() && deviceKey == null) return Retained(ok = false, keepPendingKey = false)
        val keys = candidateKeys(deviceKey)
        val openedBlobs = HashSet<String>()
        var union = emptyList<MeetupBadge>()
        for (blob in blobs) {
            for (key in keys) {
                val badges = decryptBlob(blob, key) ?: continue
                openedBlobs += blob
                union = BadgeIdentity.unionByIdentity(union, badges)
                break
            }
        }
        if (legacy.isNotEmpty()) union = BadgeIdentity.unionByIdentity(union, legacy)
        val entries = ArrayList<JsonObject>()
        if (deviceKey != null && union.isNotEmpty()) {
            try {
                entries += formerEntry(reason, npub, encryptBlob(union, deviceKey))
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Archiv-Fassung nicht verschlüsselt", e)
                return Retained(ok = false, keepPendingKey = false)
            }
            for (blob in blobs) if (blob !in openedBlobs) entries += formerEntry(reason, npub, blob)
        } else {
            for (blob in blobs) entries += formerEntry(reason, npub, blob)
        }
        val pendingKept = deviceKey == null ||
            (pendingBlob != null && pendingBlob.contains(':') && pendingBlob !in openedBlobs)
        return Retained(ok = appendFormer(entries), keepPendingKey = pendingKept)
    }

    private fun formerEntry(reason: String, npub: String, blob: String): JsonObject = jsonObjectOf(
        "at" to timeSource.nowMillis() / 1000,
        "reason" to reason,
        "npub" to npub,
        "blob" to blob,
    )

    /** Appends entries (skips blobs without `:` and duplicates); keeps at most 10 / 500 Ki chars, never drops the newest. */
    private suspend fun appendFormer(additions: List<JsonObject>): Boolean {
        if (additions.isEmpty()) return true
        val list = loadFormer().toMutableList()
        var added = false
        for (entry in additions) {
            val blob = stringOf(entry["blob"])
            if (!blob.contains(':')) continue
            if (list.any { (it["blob"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content == blob }) continue
            list += entry
            added = true
        }
        if (!added) return true
        while (list.size > 1 && (list.size > FORMER_MAX || DartJson.encode(list).length > FORMER_MAX_CHARS)) {
            list.removeAt(0)
        }
        return writeFormer(list)
    }

    /** Prefs and second copy united by blob; a list only in the secure store comes back into prefs. */
    private suspend fun loadFormer(): List<JsonObject> {
        val fromPrefs = parseFormer(prefs.getString(BadgeStoreKeys.FORMER))
        if (resetStillPending()) return fromPrefs
        val secureRaw = when (val r = secure.readChecked(BadgeStoreKeys.FORMER)) {
            is SecureRead.Present -> r.value
            SecureRead.Absent -> null
            is SecureRead.Unreadable -> {
                AppLogger.warn(TAG, "Archiv-Zweitkopie nicht lesbar (${r.reason})")
                null
            }
        }
        val merged = unionFormer(fromPrefs, parseFormer(secureRaw))
        if (merged.size != fromPrefs.size) prefs.setString(BadgeStoreKeys.FORMER, DartJson.encode(merged))
        return merged
    }

    /** `false`: prefs did not take the list; the second copy keeps the last good state then. */
    private suspend fun writeFormer(list: List<JsonObject>): Boolean {
        val encoded = DartJson.encode(list)
        if (!commitString(BadgeStoreKeys.FORMER, encoded)) {
            AppLogger.warn(TAG, "Archiv nicht geschrieben")
            return false
        }
        if (!secure.write(BadgeStoreKeys.FORMER, encoded)) AppLogger.warn(TAG, "Archiv-Zweitkopie nicht geschrieben")
        return true
    }

    private suspend fun deleteFormer(): Boolean {
        prefs.remove(BadgeStoreKeys.FORMER)
        return deleteSecureKey(BadgeStoreKeys.FORMER)
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun legacyBadges(): List<MeetupBadge> =
        parseEach(prefs.getStringList(BadgeStoreKeys.LEGACY).orEmpty()) +
            parseEach(prefs.getStringList(BadgeStoreKeys.LEGACY_PENDING).orEmpty())

    /** `commit` = written AND re-read equals the value. */
    private suspend fun commitString(key: String, value: String): Boolean =
        prefs.setString(key, value) && prefs.getString(key) == value

    companion object {
        private const val TAG = "Badge"
        private const val KEY_BYTES = 32
        private const val FORMER_MAX = 10
        private const val FORMER_MAX_CHARS = 500 * 1024
        internal const val REASON_REPLACED = "replaced"
        internal const val REASON_ROTATED = "rotated"
        internal const val REASON_CLEARED = "cleared"
        private const val BACKUP_SUFFIX = "_BACKUP"

        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

        /** 64 hex chars after Dart trim → 32-byte key, else `null`. */
        internal fun keyFromHex(raw: String?): ByteArray? {
            if (raw == null) return null
            val trimmed = DartString.trim(raw)
            if (!HEX64.matches(trimmed)) return null
            return Hex.decode(trimmed)
        }

        internal fun encryptBlob(badges: List<MeetupBadge>, key: ByteArray): String =
            AesGcmBlob.seal(key, MeetupBadge.listToJsonString(badges))

        /** Dart `_decryptBlob`: any failure (format, key, JSON, badge types) → `null`. */
        internal fun decryptBlob(blob: String, key: ByteArray): List<MeetupBadge>? = try {
            MeetupBadge.listFromJsonString(AesGcmBlob.open(blob, key))
        } catch (e: Exception) {
            null
        }

        private fun parseEach(items: List<String>): List<MeetupBadge> =
            items.mapNotNull { runCatching { MeetupBadge.fromJsonString(it) }.getOrNull() }

        private fun parseFormer(raw: String?): List<JsonObject> {
            if (raw.isNullOrEmpty()) return emptyList()
            val list = runCatching { DartJson.decode(raw) }.getOrNull() as? JsonArray ?: return emptyList()
            return list.filterIsInstance<JsonObject>()
        }

        /** Dedupe by non-empty blob (first wins), then stable sort by `at` (missing = 0). */
        private fun unionFormer(first: List<JsonObject>, second: List<JsonObject>): List<JsonObject> {
            val seen = HashSet<String>()
            val out = ArrayList<JsonObject>()
            for (entry in first + second) {
                val blob = stringOf(entry["blob"])
                if (blob.isEmpty() || !seen.add(blob)) continue
                out += entry
            }
            return out.withIndex().sortedWith(compareBy<IndexedValue<JsonObject>> { atOf(it.value) }.thenBy { it.index })
                .map { it.value }
        }

        /** `(e['at'] as num?)?.toInt() ?? 0`. */
        private fun atOf(entry: JsonObject): Long {
            val at = entry["at"] ?: return 0
            return at.dartIntOrNull() ?: at.dartNumOrNull()?.let { if (it.isFinite()) it.dartToInt() else 0L } ?: 0
        }

        /** Dart `(value ?? '').toString()`. */
        private fun stringOf(value: JsonElement?): String =
            if (value == null || value is JsonNull) "" else BadgeSecurity.dartToString(value)
    }
}
