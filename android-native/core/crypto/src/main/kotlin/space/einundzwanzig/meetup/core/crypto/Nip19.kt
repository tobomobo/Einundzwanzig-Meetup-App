package space.einundzwanzig.meetup.core.crypto

/**
 * NIP-19 bech32 entities, a port of `Nip19` from `package:nostr` 1.5.0 (npub/nsec/note) plus
 * TLV entities (nprofile/nevent/naddr) for links and QR codes.
 *
 * Dart quirks kept on purpose:
 * - `decodeNpub`/`decodeNsec`/`decodeNote` return `""` (no exception) when the HRP is different;
 *   bech32 errors (checksum, mixed case, > 90 chars, bad padding) throw [Bech32Exception].
 * - The payload length is not checked; the result is the lowercase hex of whatever was encoded.
 * - Encoding accepts upper- or lowercase hex of any length (bech32 length limit 90 applies).
 */
object Nip19 {
    const val HRP_NPUB = "npub"
    const val HRP_NSEC = "nsec"
    const val HRP_NOTE = "note"
    const val HRP_NPROFILE = "nprofile"
    const val HRP_NEVENT = "nevent"
    const val HRP_NADDR = "naddr"

    /** TLV entities are longer than 90 characters; nostr-tools uses 5000 as well. */
    const val TLV_MAX_LENGTH = 5000

    fun encodeNpub(pubkeyHex: String): String = encodeHex(HRP_NPUB, pubkeyHex)
    fun encodeNsec(privkeyHex: String): String = encodeHex(HRP_NSEC, privkeyHex)
    fun encodeNote(eventIdHex: String): String = encodeHex(HRP_NOTE, eventIdHex)

    fun decodeNpub(npub: String): String = decodeIfHrp(npub, HRP_NPUB)
    fun decodeNsec(nsec: String): String = decodeIfHrp(nsec, HRP_NSEC)
    fun decodeNote(note: String): String = decodeIfHrp(note, HRP_NOTE)

    /** `bech32Decode` of package:nostr: HRP plus lowercase hex payload. */
    fun decode(value: String): Pair<String, String> {
        val (hrp, bytes) = Bech32.decodeBytes(value)
        return hrp to bytes.toHex()
    }

    /** `s.startsWith("npub1")` (case-sensitive) and decoding does not throw. */
    fun isValidNpub(value: String): Boolean =
        value.startsWith("${HRP_NPUB}1") && runCatching { decodeNpub(value) }.isSuccess

    fun isValidNsec(value: String): Boolean =
        value.startsWith("${HRP_NSEC}1") && runCatching { decodeNsec(value) }.isSuccess

    /** `NostrService.shortenNpub`: `npub1abcd...wxyz` with three ASCII dots. */
    fun shortenNpub(npub: String, chars: Int = 8): String =
        if (npub.length < chars * 2 + 5) npub
        else npub.substring(0, 4 + chars) + "..." + npub.substring(npub.length - chars)

    // ---------- TLV (NIP-19 shareable identifiers) ----------

    data class Profile(val pubkeyHex: String, val relays: List<String> = emptyList())

    data class EventPointer(
        val idHex: String,
        val relays: List<String> = emptyList(),
        val authorHex: String? = null,
        val kind: Long? = null,
    )

    data class AddressPointer(
        val identifier: String,
        val pubkeyHex: String,
        val kind: Long,
        val relays: List<String> = emptyList(),
    )

    fun encodeNprofile(profile: Profile): String = encodeTlv(
        HRP_NPROFILE,
        buildList {
            add(TLV_SPECIAL to profile.pubkeyHex.hexToBytes())
            profile.relays.forEach { add(TLV_RELAY to DartUtf8.encode(it)) }
        },
    )

    fun encodeNevent(pointer: EventPointer): String = encodeTlv(
        HRP_NEVENT,
        buildList {
            add(TLV_SPECIAL to pointer.idHex.hexToBytes())
            pointer.relays.forEach { add(TLV_RELAY to DartUtf8.encode(it)) }
            pointer.authorHex?.let { add(TLV_AUTHOR to it.hexToBytes()) }
            pointer.kind?.let { add(TLV_KIND to uint32(it)) }
        },
    )

    fun encodeNaddr(pointer: AddressPointer): String = encodeTlv(
        HRP_NADDR,
        buildList {
            add(TLV_SPECIAL to DartUtf8.encode(pointer.identifier))
            pointer.relays.forEach { add(TLV_RELAY to DartUtf8.encode(it)) }
            add(TLV_AUTHOR to pointer.pubkeyHex.hexToBytes())
            add(TLV_KIND to uint32(pointer.kind))
        },
    )

    fun decodeNprofile(value: String): Profile {
        val tlv = decodeTlv(value, HRP_NPROFILE)
        val pubkey = tlv.single32(TLV_SPECIAL, "pubkey")
        return Profile(pubkey.toHex(), tlv.relays())
    }

    fun decodeNevent(value: String): EventPointer {
        val tlv = decodeTlv(value, HRP_NEVENT)
        return EventPointer(
            idHex = tlv.single32(TLV_SPECIAL, "event id").toHex(),
            relays = tlv.relays(),
            authorHex = tlv[TLV_AUTHOR]?.firstOrNull()?.also { requireLen(it, 32, "author") }?.toHex(),
            kind = tlv[TLV_KIND]?.firstOrNull()?.let { readUint32(it) },
        )
    }

    fun decodeNaddr(value: String): AddressPointer {
        val tlv = decodeTlv(value, HRP_NADDR)
        val identifier = tlv[TLV_SPECIAL]?.firstOrNull() ?: throw Bech32Exception("Missing identifier")
        val author = tlv[TLV_AUTHOR]?.firstOrNull() ?: throw Bech32Exception("Missing author")
        requireLen(author, 32, "author")
        val kind = tlv[TLV_KIND]?.firstOrNull() ?: throw Bech32Exception("Missing kind")
        return AddressPointer(
            identifier = DartUtf8.decodeStrict(identifier),
            pubkeyHex = author.toHex(),
            kind = readUint32(kind),
            relays = tlv.relays(),
        )
    }

    // ---------- intern ----------

    private const val TLV_SPECIAL = 0
    private const val TLV_RELAY = 1
    private const val TLV_AUTHOR = 2
    private const val TLV_KIND = 3

    private fun encodeHex(hrp: String, hex: String): String = Bech32.encodeBytes(hrp, hex.hexToBytes())

    private fun decodeIfHrp(value: String, hrp: String): String {
        val (decodedHrp, hex) = decode(value)
        return if (decodedHrp == hrp) hex else ""
    }

    private fun encodeTlv(hrp: String, entries: List<Pair<Int, ByteArray>>): String {
        val out = java.io.ByteArrayOutputStream()
        for ((type, value) in entries) {
            if (value.size > 255) throw Bech32Exception("TLV value too long")
            out.write(type)
            out.write(value.size)
            out.write(value)
        }
        return Bech32.encodeBytes(hrp, out.toByteArray(), TLV_MAX_LENGTH)
    }

    private fun decodeTlv(value: String, hrp: String): Map<Int, List<ByteArray>> {
        val (decodedHrp, data) = Bech32.decodeBytes(value, TLV_MAX_LENGTH)
        if (decodedHrp != hrp) throw Bech32Exception("Expected $hrp, got $decodedHrp")
        val result = LinkedHashMap<Int, MutableList<ByteArray>>()
        var i = 0
        while (i < data.size) {
            if (i + 2 > data.size) throw Bech32Exception("Truncated TLV")
            val type = data[i].toInt() and 0xff
            val len = data[i + 1].toInt() and 0xff
            if (i + 2 + len > data.size) throw Bech32Exception("Truncated TLV")
            result.getOrPut(type) { mutableListOf() }.add(data.copyOfRange(i + 2, i + 2 + len))
            i += 2 + len
        }
        return result
    }

    private fun Map<Int, List<ByteArray>>.single32(type: Int, label: String): ByteArray {
        val v = this[type]?.firstOrNull() ?: throw Bech32Exception("Missing $label")
        requireLen(v, 32, label)
        return v
    }

    private fun Map<Int, List<ByteArray>>.relays(): List<String> =
        this[TLV_RELAY].orEmpty().map { DartUtf8.decodeStrict(it) }

    private fun requireLen(v: ByteArray, len: Int, label: String) {
        if (v.size != len) throw Bech32Exception("$label must be $len bytes, has ${v.size}")
    }

    private fun uint32(v: Long): ByteArray {
        require(v in 0..0xffffffffL) { "kind out of range: $v" }
        return byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }

    private fun readUint32(b: ByteArray): Long {
        requireLen(b, 4, "kind")
        return ((b[0].toLong() and 0xff) shl 24) or ((b[1].toLong() and 0xff) shl 16) or
            ((b[2].toLong() and 0xff) shl 8) or (b[3].toLong() and 0xff)
    }
}
