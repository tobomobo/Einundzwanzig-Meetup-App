package space.einundzwanzig.meetup.data.proofs.internal

import space.einundzwanzig.meetup.core.data.prefs.KeyValueEditor
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.prefs.asString

/**
 * Local metadata only; Flutter proof entries and backups keep their original format.
 *
 * The durable generation is checked INSIDE the proof transaction, not before acquiring the
 * preferences transaction. Each generation is bound to the intended next identity in that
 * same transaction: work started after cleanup but before key persistence cannot write for
 * the departing identity, even if the incoming identity has not saved any proof yet.
 * Missing ownership means a legacy/migrated/backup value, belonging to the current identity.
 */
internal class ProofOwnership(private val store: KeyValueStore, private val identity: ProofsIdentity) {
    class Snapshot(val generation: PrefValue?, val npub: String?) {
        fun owns(editor: KeyValueEditor, key: String): Boolean = belongsTo(editor[key], npub)

        fun stamp(editor: KeyValueEditor, key: String) {
            if (npub == null) editor.remove(key) else editor.setString(key, npub)
        }
    }

    suspend fun snapshot(): Snapshot = Snapshot(store.get(GENERATION), identity.npub())

    suspend fun edit(snapshot: Snapshot, onInvalidated: () -> Unit = {}, block: KeyValueEditor.() -> Unit): Boolean {
        var accepted = false
        val saved = store.edit {
            accepted = get(GENERATION) == snapshot.generation && isBoundTo(get(IDENTITY), snapshot.npub)
            if (accepted) block() else onInvalidated()
        }
        return saved && accepted
    }

    /** Read payload and owner from one snapshot; never pair a pre-switch legacy value with a new key. */
    suspend fun read(ownerKey: String): Map<String, PrefValue> {
        val npub = identity.npub()
        val values = store.getAll()
        return if (isBoundTo(values[IDENTITY], npub) && belongsTo(values[ownerKey], npub) && identity.npub() == npub) values else emptyMap()
    }

    companion object {
        const val GENERATION = "native_proof_identity_generation"
        const val IDENTITY = "native_proof_identity_npub"
        const val PLATFORM_OWNER = "native_platform_proofs_owner"
        const val HUMANITY_OWNER = "native_humanity_proof_owner"

        fun belongsTo(owner: PrefValue?, npub: String?): Boolean = owner == null || owner.asString == npub && npub != null

        // Absence keeps Flutter/migrated preferences compatible. An explicit empty binding
        // represents logout; unlike absence it must reject the departing non-null identity.
        private fun isBoundTo(binding: PrefValue?, npub: String?): Boolean =
            binding == null || binding.asString == npub.orEmpty()

        fun advanceGeneration(editor: KeyValueEditor) {
            editor.setInt(GENERATION, (editor.getInt(GENERATION) ?: 0L) + 1L)
        }

        fun bind(editor: KeyValueEditor, nextNpub: String?) {
            editor.setString(IDENTITY, nextNpub.orEmpty())
        }

        fun invalidate(editor: KeyValueEditor) {
            advanceGeneration(editor)
            // A later badgesync wipe must retain the transition's binding.
            editor.remove(PLATFORM_OWNER)
            editor.remove(HUMANITY_OWNER)
        }
    }
}
