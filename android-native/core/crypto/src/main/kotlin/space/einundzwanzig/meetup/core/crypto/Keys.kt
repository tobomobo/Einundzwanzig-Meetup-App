package space.einundzwanzig.meetup.core.crypto

/**
 * secp256k1 key helpers (`bip340.getPublicKey`, `Keychain` of `package:nostr`).
 *
 * Unlike the Dart code, private keys outside 1..n-1 are rejected (Dart would crash or silently
 * reduce mod n); a random 32-byte key hits that range with probability ~2^-128.
 */
object Keys {
    /** 32 random bytes that form a valid private key (`Keychain.generate()`). */
    fun generatePrivateKey(): ByteArray {
        while (true) {
            val candidate = CryptoRandom.bytes(32)
            if (isValidPrivateKey(candidate)) return candidate
        }
    }

    fun generatePrivHex(): String = generatePrivateKey().toHex()

    fun isValidPrivateKey(priv: ByteArray): Boolean =
        priv.size == 32 && runCatching { Secp256k1Native.lib.secKeyVerify(priv) }.getOrDefault(false)

    /** 64 hex chars (either case) encoding a key in 1..n-1. */
    fun isValidPrivHex(privHex: String): Boolean =
        Hex.isHexOfLength(privHex, 64) && isValidPrivateKey(privHex.hexToBytes())

    /** BIP-340 x-only public key (32 bytes) of [priv]. */
    fun xOnlyPublicKey(priv: ByteArray): ByteArray {
        require(isValidPrivateKey(priv)) { "Invalid private key" }
        val uncompressed = Secp256k1Native.lib.pubkeyCreate(priv) // 0x04 | x | y
        return uncompressed.copyOfRange(1, 33)
    }

    /** `bip340.getPublicKey(privHex)` — 64 lowercase hex chars. */
    fun pubHexFromPriv(privHex: String): String = xOnlyPublicKey(requirePrivHex(privHex)).toHex()

    /**
     * Lifts an x-only key to the point with even y, returned compressed (`0x02 | x`).
     * Throws [IllegalArgumentException] when x is not on the curve (twist points included).
     */
    fun liftX(xOnly: ByteArray): ByteArray {
        require(xOnly.size == 32) { "x-only public key must be 32 bytes, has ${xOnly.size}" }
        val compressed = byteArrayOf(0x02) + xOnly
        val parsed = runCatching { Secp256k1Native.lib.pubkeyParse(compressed) }.getOrNull()
        require(parsed != null) { "Public key is not on the curve" }
        return compressed
    }

    /** True if [pubHex] is 64 hex chars whose x lies on secp256k1. */
    fun isValidPubHex(pubHex: String): Boolean =
        Hex.isHexOfLength(pubHex, 64) && runCatching { liftX(pubHex.hexToBytes()) }.isSuccess

    /**
     * `BackupService.backupKeyConsistent` (security audit N3): the nsec decodes to [privHex]
     * (trimmed, case-insensitive), [privHex] is 64 lowercase-normalised hex chars, and its public
     * key encodes to [npub] (trimmed). Any error → false.
     */
    fun backupKeyConsistent(nsec: String, npub: String, privHex: String): Boolean {
        return try {
            val privFromNsec = Nip19.decodeNsec(nsec.trim()).lowercase()
            val priv = privHex.trim().lowercase()
            privFromNsec == priv &&
                Regex("^[0-9a-f]{64}$").matches(priv) &&
                Nip19.encodeNpub(pubHexFromPriv(priv)) == npub.trim()
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    internal fun requirePrivHex(privHex: String): ByteArray {
        require(Hex.isHexOfLength(privHex, 64)) { "Private key must be 64 hex characters" }
        val bytes = privHex.hexToBytes()
        require(isValidPrivateKey(bytes)) { "Private key outside the curve order" }
        return bytes
    }
}

/** BIP-340 Schnorr signatures (`package:bip340` 0.2.0, as used by `Event.from` / `isValid`). */
object Schnorr {
    /**
     * Signs a 32-byte message (a Nostr event id). [auxRand] must be 32 bytes; production code
     * uses fresh randomness (the default), tests pass zeros for deterministic vectors.
     */
    fun sign(message32: ByteArray, priv32: ByteArray, auxRand: ByteArray = CryptoRandom.bytes(32)): ByteArray {
        require(message32.size == 32) { "Message must be 32 bytes" }
        require(auxRand.size == 32) { "aux_rand must be 32 bytes" }
        require(Keys.isValidPrivateKey(priv32)) { "Invalid private key" }
        return Secp256k1Native.lib.signSchnorr(message32, priv32, auxRand)
    }

    /** BIP-340 verification; malformed input yields false, never an exception. */
    fun verify(sig64: ByteArray, message32: ByteArray, xOnlyPub32: ByteArray): Boolean {
        if (sig64.size != 64 || message32.size != 32 || xOnlyPub32.size != 32) return false
        return runCatching { Secp256k1Native.lib.verifySchnorr(sig64, message32, xOnlyPub32) }
            .getOrDefault(false)
    }

    /**
     * `bip340.verify(pubkey, message, signature)` on hex strings (either case). Requires exactly
     * 64/64/128 hex characters — stricter than bip340's left-padding, which only matters for
     * malformed events.
     */
    fun verifyHex(pubkeyHex: String, messageHex: String, sigHex: String): Boolean {
        if (!Hex.isHexOfLength(pubkeyHex, 64) || !Hex.isHexOfLength(messageHex, 64) ||
            !Hex.isHexOfLength(sigHex, 128)
        ) {
            return false
        }
        return verify(sigHex.hexToBytes(), messageHex.hexToBytes(), pubkeyHex.hexToBytes())
    }

    /** `bip340.sign(privHex, messageHex, auxHex)` → 128 lowercase hex chars. */
    fun signHex(messageHex: String, privHex: String, auxRand: ByteArray = CryptoRandom.bytes(32)): String =
        sign(messageHex.hexToBytes(), Keys.requirePrivHex(privHex), auxRand).toHex()
}

/** A local Nostr identity: private key plus derived x-only public key. */
class KeyPair private constructor(private val priv: ByteArray) {
    val publicKey: ByteArray = Keys.xOnlyPublicKey(priv)

    val privateKeyHex: String get() = priv.toHex()
    val publicKeyHex: String = publicKey.toHex()
    val npub: String get() = Nip19.encodeNpub(publicKeyHex)
    val nsec: String get() = Nip19.encodeNsec(privateKeyHex)

    fun privateKey(): ByteArray = priv.copyOf()

    fun sign(message32: ByteArray, auxRand: ByteArray = CryptoRandom.bytes(32)): ByteArray =
        Schnorr.sign(message32, priv, auxRand)

    override fun toString(): String = "KeyPair(pub=$publicKeyHex)"

    override fun equals(other: Any?): Boolean = other is KeyPair && other.priv.contentEquals(priv)

    override fun hashCode(): Int = publicKeyHex.hashCode()

    companion object {
        fun generate(): KeyPair = KeyPair(Keys.generatePrivateKey())

        /** 64 hex chars (either case), 1 ≤ d < n; else [IllegalArgumentException]. */
        fun fromPrivateKeyHex(privHex: String): KeyPair = KeyPair(Keys.requirePrivHex(privHex))

        fun fromPrivateKey(priv: ByteArray): KeyPair {
            require(Keys.isValidPrivateKey(priv)) { "Invalid private key" }
            return KeyPair(priv.copyOf())
        }

        /** `nsec1…` → key pair; throws [IllegalArgumentException] (incl. [Bech32Exception]). */
        fun fromNsec(nsec: String): KeyPair {
            val hex = Nip19.decodeNsec(nsec)
            require(hex.isNotEmpty()) { "Not an nsec" }
            return fromPrivateKeyHex(hex)
        }
    }
}
