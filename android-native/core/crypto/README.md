# :core:crypto

Pure crypto and encoding primitives of the native app, byte-compatible with the Flutter app.
Package `space.einundzwanzig.meetup.core.crypto`. No Android framework code, no DI: plain Kotlin
`object`s and small value classes. Everything that crosses the wire (event ids, signatures,
NIP-19/44/49 strings, backup files, badge blobs, hash inputs) is verified against vectors produced
by the Dart code or by the official NIP test vectors.

Dependencies: `fr.acinq.secp256k1` (libsecp256k1 via JNI: BIP-340, key derivation, point
multiplication) and BouncyCastle's lightweight API (`bcprov`; ChaCha20, Poly1305, HKDF, scrypt,
bcrypt, GCM, PBKDF2). javax is used for SHA-256, HMAC and AES-CBC. `:core:common` (as
`implementation`) supplies the Dart-compatible JSON writer `DartJson`
(`space.einundzwanzig.meetup.core.common.json`), which `EventIds` uses for the id serialization.
There is no JSON codec in this module: use `:core:common`'s `DartJson` (`encode`,
`canonicalJsonEncode`, `quote`, `decode`) and `Double.toDartString()` directly.

`DartUtf8` and `DartBase64` stay in this module on purpose: nearly every caller hashes, signs or
encrypts the bytes anyway, and `:core:common` cannot depend on crypto. A data module that only
needs Dart-compatible UTF-8/base64 (QR or deep-link payloads) depends on `:core:crypto` for them;
do not write another copy. `:core:common`'s `DartUri` has a small private UTF-8 percent-encoder
with the same lone-surrogate → U+FFFD rule; it is internal to URI escaping, not a public codec.

Tests read the shared `NN-*-vectors.json` directly from `android-native/spec/fixtures` (no copies
in `src/test/resources`; only `nip44.vectors.json` is a resource).

## API and the Dart code it mirrors

| Kotlin | Purpose | Dart source |
|---|---|---|
| `Hex`, `ByteArray.toHex()`, `String.hexToBytes()` | lowercase hex encode, case-insensitive decode, `isHexOfLength` (`isValidPubkeyHex`/`isValidSignatureHex`) | `package:convert` `hex`, `badge_security.dart` |
| `DartUtf8` | `encode` (lone surrogates → U+FFFD), `decodeStrict`, `decodeLenient` (`allowMalformed: true`), leading BOM dropped like Dart | `dart:convert` `utf8` |
| `DartBase64` | `encode`, Dart-strict `decode` (std + url alphabet, padding/trailing-bit checks), `encodeUrl`, `encodeUrlNoPadding`, `decodeUrlLenient` | `dart:convert` `base64`, `passkey_prf_service.dart` |
| `Sha256`, `HmacSha256`, `CryptoRandom`, `constantTimeEquals` | hashes (`hexOfUtf8` = `sha256.convert(utf8.encode(s)).toString()`), HMAC with any key length (also empty), CSPRNG | `package:crypto`, `rolling_qr_service.dart`, proof hashes |
| `Bech32` | classic bech32 (BIP-173) with the validation order and 90-char default limit of `package:bech32`; `convertBits` (strict), `convertBitsUnchecked` (bolt11) | `package:bech32` 0.2.2, `nostr` `nip_019.dart` |
| `Nip19` | `encodeNpub/Nsec/Note`, `decodeNpub/Nsec/Note` (return `""` on a different HRP — Dart quirk), `decode`, `isValidNpub/Nsec`, `shortenNpub`, TLV `nprofile/nevent/naddr` encode/decode | `nostr` 1.5.0 `Nip19`, `NostrService.shortenNpub` |
| `Keys` | `generatePrivateKey/PrivHex`, `isValidPrivHex`, `xOnlyPublicKey`, `pubHexFromPriv`, `liftX`, `isValidPubHex`, `backupKeyConsistent` | `bip340.getPublicKey`, `Keychain`, `BackupService.backupKeyConsistent` |
| `Schnorr` | BIP-340 `sign(msg32, priv32, auxRand32)` (random aux by default, pass zeros for vectors), `verify`, `signHex`, `verifyHex` (never throws) | `package:bip340` 0.2.0 |
| `KeyPair` | `generate`, `fromPrivateKeyHex`, `fromPrivateKey`, `fromNsec`; `publicKeyHex`, `npub`, `nsec`, `sign` | `Keychain` |
| `EventIds`, `SignedNostrEvent` | `serialize` (`[0,pubkey,created_at,kind,tags,content]`), `compute`, `computeBytes`, `sign` (= `Event.from`), `isValid` (= `Event.isValid`: lowercase pubkey, 10-char `created_at`, case-sensitive id, BIP-340); `toJson`, `toEventMessage` | `nostr` 1.5.0 `Event`, `SignedEvent` in `signing_service.dart` |
| `Nip44` | v2 `conversationKey` (raw ECDH x, not libsecp's hashed `ecdh()`), `encrypt` (test-only `nonce`), `decrypt`, `messageKeys`, `calcPaddedLen`, `Nip44Exception` | `lib/services/nip44.dart` |
| `Nip49`, `KeySecurity` | `encrypt` (logN 1..22, default 16; test-only `salt`/`nonce`), `decrypt`, `keySecurityOf`, `scrypt`, `debugDecodePayload/EncodePayload`, `Nip49Exception` | `lib/services/nip49.dart` |
| `XChaCha20Poly1305` | `seal`/`open` (ciphertext ‖ tag), `hChaCha20` | `package:cryptography` `Xchacha20.poly1305Aead()` |
| `Pbkdf2` | `hmacSha256(password bytes or String, salt, iterations, keyBytes)`, `BACKUP_ITERATIONS = 600 000` | `lib/services/pbkdf2/pbkdf2_dart.dart` |
| `AesGcm` | AES-GCM, 128-bit tag, any IV length (12 for badges, 16 for backups), `decryptIvPrefixed` | `package:encrypt` `AES(mode: gcm)` |
| `AesGcmBlob` | `base64(iv):base64(ct‖tag)` badge store blobs: `seal`, `open`, `openOrNull` | `MeetupBadge` storage in `models/badge.dart` |
| `BackupEnvelope` | `.21bkp` envelope: `enc_v2:` (PBKDF2 600k + AES-GCM, 32-byte salt, 16-byte IV), legacy `enc_v1:` (SHA-256 key), plain passthrough | `BackupService.exportData/importData` |
| `AesCbc` | AES-CBC/PKCS#7 (legacy flutter_secure_storage values, `iv16 ‖ ct`) | flutter_secure_storage 9.x cipher (migration) |
| `Bcrypt` | `hashpw`, `checkpw`, `gensalt` (`$2b$`), `$2a/$2b/$2y/$2` parsing, 72-byte limit | `package:dbcrypt` 2.0.0 (no longer called by the app) |
| `Bolt11` | `descriptionHashHex(bolt11)` (tagged field `h`) | `ZapReceiptValidator.descriptionHashOf` |

Exceptions: `Nip44Exception`, `Nip49Exception`, `Bech32Exception` (an `IllegalArgumentException`),
`AeadAuthenticationException`, `BackupCryptoException`; invalid arguments elsewhere throw
`IllegalArgumentException`.

## Usage notes

- scrypt (NIP-49, 64 MiB at log_n 16) and PBKDF2 (600 000 rounds) take seconds: call them on
  `Dispatchers.Default`, never on the main thread. NIP-49 at log_n 16 can throw `OutOfMemoryError`
  on very small heaps.
- Production code never passes `nonce`/`salt`/`iv`/`auxRand`; those parameters exist for test
  vectors.
- Modules whose **JVM unit tests** reach secp256k1 code (signing, NIP-44, key derivation) need
  `testImplementation(libs.secp256k1.jvm)`; the Android AAR only contains device ABIs and its loader
  falls back to the JVM artifact.
- Use `EventIds` / `:core:common` `DartJson` for every hashed or signed JSON string. kotlinx.serialization,
  org.json, Gson & co. differ in escaping or number formatting.

## Tests

`tools/gradlew-locked :core:crypto:testDebugUnitTest` — 101 JVM tests, ~3 s. The `jsonEncode`
parity cases (doubles, escaping, kotlinx number re-reading, canonical JSON vectors) moved to
`:core:common` `DartJsonTest` together with the codec.

Ported Dart tests: `nip44_vectors_test`, `nip44_web_smoke_test`, `nip49_test`,
`pbkdf2_equality_test` (+ the 600k value of `pbkdf2_web_equality_test`), the crypto parts of
`relay_event_signature_test`, `signer_nip44_test`, `local_easy_auth_test`, `badge_security_test`,
`stage_a_security_test` (N3) and `zap_receipt_validator_test` (`descriptionHashOf`).
`local_key_vault_test` and `meetup_key_test` contain no crypto (storage / string logic) and belong to
the data modules.

Vectors (`src/test/resources`): `nip44.vectors.json` (verbatim from `<repo>/test/fixtures`, sha256
`269ed0f6…25040`, all 128 official cases) and verbatim copies of `spec/fixtures/*-vectors.json`
(01, 02, 04, 07, 08, 14: NIP-19, event ids incl. the escaping vector, BIP-340 signatures with zero
aux, canonical JSON, rolling-QR HMAC, proof hashes, AES-GCM badge blobs, `enc_v2`/`enc_v1` backups,
flutter_secure_storage GCM/CBC values). Additional expected values in the tests were produced by
running the app's Dart packages (`bip340`, `bech32`, `dbcrypt`, `encrypt`, `cryptography`,
`pointycastle`, `unorm_dart`, Dart SDK `jsonEncode`/`utf8`/`base64`) on the same inputs.

## Known deviations from the Dart code

All are stricter-or-equal and only affect malformed input:

- `DartBase64.decode` does not accept a percent-encoded `%3D` as padding (Dart does).
- `Schnorr.verifyHex`/`EventIds.isValid` require exactly 64/128 hex chars; `bip340` 0.2.0
  left-pads shorter pubkeys/signatures.
- Private keys outside `1..n-1` are rejected (`KeyPair`, `Keys`); Dart's `bip340` would crash
  (0) or reduce mod n.
- A NIP-44 plaintext that is not valid UTF-8 after a correct MAC throws `Nip44Exception`
  (Dart: `FormatException`).
- NFKC uses `java.text.Normalizer` (platform Unicode tables) instead of `unorm_dart`.
- NIP-04 is not implemented: the Dart app never uses it.
