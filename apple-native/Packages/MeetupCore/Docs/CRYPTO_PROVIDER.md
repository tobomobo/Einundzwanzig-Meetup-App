# Cryptographic provider: source, trust, and CI

This note records the source inspected on 2026-10-21 for the `MeetupNostr`
prototype. It is a focused integration review and proof of concept, not a
security audit of the Swift wrapper, build plugin, vendored code, or application.

## Module boundary

`MeetupNostr` is a separate library product/target. `MeetupCore` does not depend
on it and its security capability flags remain disabled. No relay transport,
app private-key UI/storage, organization authorization, or badge/claim enablement
is part of this foundation.

The public `NostrCrypto` actor exposes `derivePublicKey(privateKey:)`,
`signEventID(_:privateKey:)`, `verifyEventID(_:publicKey:signature:)`,
`sign(_:privateKey:)`, and `verify(_:)`. Keys are ephemeral `Data` inputs to
individual operations; the actor retains no key property. All returned values
are `Data`, `Bool`, or immutable `Sendable` event models. The only explicit
auxiliary-randomness signing entry point is internal and used through
`@testable import` for published vectors.

`UnsignedNostrEvent` and `NostrEvent` are `Codable`, `Hashable`, and `Sendable`.
Their throwing constructors/decoders enforce lowercase ASCII hex fields,
`created_at` in `0...9_007_199_254_740_991` (the cross-client exact-integer range),
`kind` in `0...65_535`, nonempty individual tag arrays, and the resource bounds
published in `NostrEventLimits`: 64 KiB content UTF-8, 2,048 tags, 64 elements per
tag, 8 KiB per tag element, and 128 KiB total canonical bytes including escaping.
These resource and timestamp limits are this adapter's acceptance bounds; they
are not an assertion that NIP-01 mandates these maxima. Use
`NostrEvent.decodeJSON(_:)` / `UnsignedNostrEvent.decodeJSON(_:)` for untrusted
wire data to apply the 256 KiB input bound **before** JSON decoding. These entry
points also check original top-level timestamp/kind number tokens, requiring
ordinary nonnegative decimal integers without fractions, exponents or signs.
This prevents Foundation from silently rounding a malformed numeric token into
an accepted integer, including fractions beyond Decimal's precision. Escaped
field names and every duplicate occurrence are checked; content remains opaque.
Foundation `JSONSerialization` first validates the whole document, including
otherwise-ignored fields that `JSONDecoder` may skip without fully checking
their string escapes/numbers. Its parsed object is discarded and never enters
the event hash. `JSONDecoder` then performs model field-type decoding.

Direct generic `JSONDecoder`/`Decoder` use validates decoded field ranges and
resource bounds, and supports normal JSON/property-list Codable roundtrips.
A generic Decoder does not expose original numeric tokens and may round numbers
during Int conversion. It cannot establish lexical integer validity or enforce
the earlier input-byte bound. Untrusted wire consumers must use `decodeJSON`,
rather than relying on generic Codable decoding alone.

Canonical serialization preserves tag order and exact string UTF-8, including
opaque JSON-looking content; it never parses/sorts a content object or normalizes
Unicode. Model equality/hashing also compares content and tags by exact UTF-8,
because Swift's ordinary `String` equality considers some distinct byte
sequences canonically equivalent. `NostrReadPolicy` supplies separate, pure
filter matching and replaceable-event clock checks for a future read consumer.
Its ordered `[NostrTagFilter]` constraints retain exact UTF-8 names and values;
they avoid Swift String-keyed dictionaries/sets that would merge some distinct
Unicode spellings. Neither
helper performs signature verification, and `verify` never applies a hidden
clock or author-trust policy. A consumer must perform both checks it needs.

## Source selection and reproducibility

Use the **source dependency**
[`21-DOT-DEV/swift-secp256k1` 0.23.2][provider-manifest], product `P256K`, at
commit `e70a10e036a55fffea31568f0af92d69b6d449cd`. The inspected checkout was
`/tmp/einundzwanzig-secp256k1-poc/build/checkouts/swift-secp256k1`.
Its [vendored libsecp256k1 metadata][c-version] identifies **v0.7.1**.
The manifest builds Swift and C source targets and a source build-tool plugin;
this integration does not consume an upstream XCFramework, binary target,
CocoaPod, or system-installed secp256k1 library.

The dependency requires **Swift 6.1 or later**: `Package.swift:1` declares tools
6.1, uses package traits, and selects Swift language mode 6 at line 107. Upstream
documents Swift 6.1 / Xcode 16.3 as its minimum supported toolchain
([GettingStarted.md:13][getting-started]). This is a minimum, not evidence that
every subsequent Xcode release has passed this project's checks. The Linux PoC
used Swift **6.2**. Default traits include `schnorrsig`; `P256K` depends on
`libsecp256k1`, while the separate `ZKP` product depends on the ZKP fork
(`Package.swift:34–40,66–90`).

Keep the manifest requirement and the resolved commit distinct:

```swift
.package(url: "https://github.com/21-DOT-DEV/swift-secp256k1", exact: "0.23.2")
```

The observed version-3 PoC `Package.resolved` has one pin, identity
`swift-secp256k1`, kind `remoteSourceControl`, with this state:

```json
{
  "revision": "e70a10e036a55fffea31568f0af92d69b6d449cd",
  "version": "0.23.2"
}
```

`exact:` constrains the release version; the lock records the selected Git
revision as well. A revision requirement instead selects a commit directly and
its resolved state need not contain a semantic `version`. They are different
resolution modes, even if their source commit matches. Check the expected URL,
identity, version, and revision in CI, and verify the fetched checkout's HEAD.
Do not treat a tag name alone as immutable provenance or silently regenerate a
lock after a mismatch.

Commit the lock belonging to each build root: the package lock for direct
SwiftPM tests and the Xcode project/workspace lock for app builds. A library's
own lock is ignored when that library is consumed by another root. Use the
toolchain's resolved-only mode in CI and fail on unexpected lock changes; normal
resolution can update an ineligible or missing lock. See SwiftPM's
[resolution documentation][resolution].

There is a further manifest-context detail: `Package.swift:111–125` returns no
development dependencies only when
`Context.gitInformation?.currentTag != nil`. Otherwise it declares Lefthook,
Tuist, SwiftFormat, SwiftLint, subtree, and DocC plugin dependencies. The PoC's
exact-release resolution produced the one-pin graph above. Do not assume the
same graph for a revision requirement, local checkout, missing tag metadata, or
another resolver. The guard tests the supplied Git context, not the spelling of
the requirement. Reinspect the resolved graph after changing either the pin or
the toolchain.

## Build plugin and CI trust

`Package.swift:59–62,66–79` attaches `SharedSourcesPlugin` to `P256K` and `ZKP`.
The inspected [Plugin.swift:19–42][plugin] returns a prebuild command using
`/bin/sh -c` and this command shape:

```sh
find '<package>/Sources/Shared' -name '*.swift' -exec cp {} '<plugin-work-directory>/' \;
```

It recursively finds Swift files and **copies them into a flat directory** that
becomes compiler input. It contains no download command. The plugin's Windows
branch returns no commands; the inspected non-Windows branch is the relevant
one for Apple and Linux builds. `find` and `cp` are resolved through `PATH`;
`/bin/sh` is an absolute executable path.

This behavior has practical consequences:

- The trusted build input includes the manifest, plugin, all copied shared
  sources, vendored Swift support code, C sources, and the compiler/toolchain.
  A source dependency still executes dependency-owned build code.
- The shell interpolates single-quoted paths without escaping embedded single
  quotes. Use a controlled checkout/build path without `'`, and a controlled
  `PATH` supplying the intended `find` and `cp` tools.
- Flattening permits duplicate basenames to overwrite each other. Inspection
  found 63 matching Swift files and no duplicate basenames at this commit.
  The plugin does not remove obsolete outputs. SwiftPM prebuild directories
  also are not automatically emptied ([SE-0303][prebuild]); use clean plugin
  outputs when changing provider versions or source layouts.

For **fresh, unattended Xcode CI** without saved plugin approval, pass
`-skipPackagePluginValidation` to the `xcodebuild` build/test/archive invocation
that executes this plugin. Interactive Xcode can instead record explicit trust
and enablement. This is Xcode's package-plugin trust/fingerprint approval gate;
the switch implicitly trusts **all package plugins in that invocation**, not
only `SharedSourcesPlugin`. Prefer that invocation-scoped flag over a global
Xcode defaults change. The [SwiftLint maintainers document this Xcode
behavior][xcode-trust].

The switch does **not** disable the plugin execution sandbox, app sandbox,
dependency resolution checks, or code signing, and it does not audit or certify
the dependency. It is separate from `-skipMacroValidation` and from sandbox
disabling options; those are not needed merely to copy these shared sources.
Review the manifest/plugin before enabling unattended execution. Build
untrusted changes in disposable jobs without production signing keys or other
unnecessary credentials.

For **Linux SwiftPM**, ordinary `swift build` / `swift test` compiles and invokes
the build-tool plugin automatically. There is no Xcode approval dialog and no
need for an `xcodebuild` flag. The PoC build script succeeded without
`--disable-sandbox` or plugin-validation overrides, and generated shared files
were present under `build/plugins/outputs/.../P256K/.../SharedSourcesPlugin/`.
However, the tested Swift 6.2 SwiftPM sandbox implementation applies
`sandbox-exec` only on macOS and returns commands unmodified on Linux
([Sandbox.swift:36–67][swiftpm-sandbox]). Do not describe Linux plugin execution
as protected by SwiftPM's macOS sandbox; any isolation must come from the CI
container/runner or surrounding execution environment.

## Hashing, signing, and input boundaries

`SHA256.hash(data:)` from the `P256K` module is available from the same shared source on Apple and
Linux. [SHA256.swift:60–66][sha256] calls `secp256k1_swift_sha256`; the
[C shim:26–30][utility] initializes, updates, and finalizes libsecp256k1's internal
SHA-256. This path does not require importing Apple's CryptoKit or adding a
separate Swift Crypto package. The vendored Swift Crypto support files remain
part of the provider source under review.

For NIP-01, compute one ordinary SHA-256 over the exact UTF-8 serialization of
`[0,pubkey,created_at,kind,tags,content]`, then pass those **32 digest bytes** to
Schnorr signing/verification. `HashDigest(bytes)` wraps existing bytes; it does
not hash them. The Schnorr `signature(for: Digest)` and
`isValidSignature(_:for:)` overloads pass that digest through without another
SHA-256. Do not substitute a tagged event-ID hash, hash the hexadecimal text,
or hash the event ID again. See [NIP-01][nip01] and
[Schnorr+Signature.swift:97–113,172–175][schnorr].

Keep these checks at the application/provider boundary:

- Require exactly 32 bytes for an event ID, x-only public key, private scalar,
  and any explicit auxiliary randomness; require exactly 64 signature bytes.
  Validate private scalars as nonzero and below the secp256k1 group order.
- `HashDigest.init` slices the first 32 bytes without a count guard; short input
  can trap and extra bytes are not rejected
  ([HashDigest.swift:45–54][digest]). The x-only key initializer merely stores
  supplied bytes ([XonlyKeyImplementation.swift:78–85][xonly]). Its later C
  parser expects a 32-byte key. Construction alone is not validation.
- `SchnorrSignature.init(dataRepresentation:)` checks 64 bytes, but
  `dataRepresentation` is a **public mutable `var`**. Verification passes its
  current buffer to C without another length check
  (`Schnorr+Signature.swift:47–64,184–195`). Construct a fresh signature from
  length-checked bytes immediately before verification, keep it local and
  immutable, and never expose the provider wrapper for later mutation.
- The raw-message signing API defaults to `strict: false` and supports arbitrary
  message lengths. The auxiliary-array overload also has no length guard before
  passing a pointer to C. The Nostr adapter must enforce its narrower sizes
  before either API, including test-only deterministic signing.

`NostrCrypto` keeps provider values inside an actor and exposes byte values and
immutable event models. In 0.23.2, Schnorr `PrivateKey`, `PublicKey`, `XonlyKey`,
and `SchnorrSignature` do **not** conform to `Sendable`; the PoC's Swift 6
type-check diagnostics confirm all four. Other provider types having
`Sendable` conformance does not change this. Keep these wrappers local to one
isolated operation; do not add retroactive `@unchecked Sendable` conformances or
send the provider's raw context pointer across the app. Actor isolation is an
integration boundary, not proof of the underlying C implementation's safety.

## Randomness and secret copies

The normal Schnorr digest-signing overload obtains fresh 32-byte auxiliary data
with `SecureBytes(count: 32)` (`Schnorr+Signature.swift:97–98`). Random key
construction and initial context randomization also use `SecureBytes`
(`PrivateKeyImplementation.swift:108–118`, `Context.swift:130–139`). The actual
vendored [RNG_boring.swift:20–42][rng] fills bytes using Swift's
`SystemRandomNumberGenerator`; the filename does not mean it calls BoringSSL
`RAND_bytes` or Apple's `SecRandomCopyBytes`. Swift documents system randomness
backends as `arc4random_buf` on Apple and `getrandom`, with `/dev/urandom`
fallback, on Linux ([Swift standard library][system-rng]).

Keep fixed/zero auxiliary data limited to published test vectors. The app-facing
signer uses the provider's fresh-randomness overload. A nil auxiliary pointer in
the lower-level API means zero auxiliary data, not a request for fresh random
bytes. Provider allocation/context failures can include preconditions or fatal
errors; not every failure is a recoverable thrown error.

The vendored `SecureBytes.Backing` clears its allocated capacity on deinit
([SecureBytes.swift:331–337][secure-bytes]); Linux's `memset_s` shim calls
`secp256k1_swift_memczero` ([Zeroization.swift:31–48][zeroization]). This is
limited to those buffers. Private-key serialization creates `Data`, signing
creates arrays and a C keypair, and callers may retain their own copies.
Neither the provider nor the adapter establishes complete erasure of copies,
stack/register contents, allocator storage, or caller-owned values. Avoid secret
logging and unnecessary retention; this primitive adapter is not a key store or
private-key UI.

## Evidence and regression checks

The local PoC lives at `/tmp/einundzwanzig-secp256k1-poc`:

- `Sources/ProviderPoC/main.swift:22–40` enforces Nostr-specific input sizes.
  Lines 47–60 reproduce official BIP-340 vector 0 using the published fixture
  scalar 3 and zero auxiliary data, confirm the 32-byte x-only key and 64-byte
  signature, demonstrate digest pass-through, and verify fresh-randomness signing.
- `main.swift:65–118` / `results.txt` cover 15 official 32-byte verification
  vectors, including 10 negative cases; deliberately reject four otherwise-valid
  variable-length BIP-340 messages under Nostr policy; and check malformed sizes,
  invalid scalars, a changed digest, and a corrupted signature. These results
  were reproduced by running the existing executable with Swift 6.2's Linux
  runtime. `CheckSendable.swift` / `sendable-diagnostics.txt` record the separate
  expected concurrency-check failures. This PoC does not establish Apple builds,
  canonical event serialization coverage, or a full security assessment.

For a primary-source event-ID fixture, the official
[NIP-59 example, “Create an event”][nip59-vector] supplies an unsigned inner event
whose ID follows NIP-01. Its exact serialization and expected SHA-256 are:

```text
[0,"611df01bfcf85c26ae65453b772d8f1dfd25c264621c0277e1fc1518686faef9",1691518405,1,[],"Are you going to the party tonight?"]
9dd003c6d3b73b74a85a9ab099469ce251653a7af76f523671ab828acd2a0ef9
```

This hash was independently reproduced with Python `hashlib.sha256` over the
UTF-8 bytes. It is an **unsigned hash fixture**, not a signed-event verification
vector. The [official BIP-340 CSV][bip340-vectors] supplies independent signature
vectors. Retain both kinds of checks, and cover canonical escaping/Unicode,
post-signing field/ID mutation, malformed lengths/scalars, and random-auxiliary
signature verification on Linux and the supported Apple CI toolchain. Exact
signature equality belongs to fixed-auxiliary vectors; normal randomized signing
should be checked by verification.

The package's `MeetupNostrTests` run these vectors through the actual adapter:
all 15 fixed-length BIP-340 verification vectors (10 negative), four valid
general-BIP-340 messages rejected by the Nostr-only 32-byte policy, exact
signatures for signing vectors 0–3, production random-auxiliary verification,
malformed lengths and scalar boundaries, independent signed events, field
tampering, Codable validation, Unicode, resource bounds, actor concurrency, and
separate filter/time policy. `Fixtures/bip340-test-vectors.csv` is the unchanged
official CSV copied from the PoC (SHA-256
`34c9d1d9c3a88d524bc80778540dc43f8306ec249a7485293063c376db851c2d`).
`Fixtures/dart-nostr-vectors.json` is copied unchanged from
`android-native/spec/fixtures/07-nostr-infra-vectors.json` (SHA-256
`e906bbd60b46b168ac1cab19652159e6d83fa2e76e3dc77d7ddca831447ae3a5`).
It supplies the existing Dart-compatible canonical escaping/ID case and two
independent signed events. The NIP-59 hash above is tested separately against
its primary published expected ID.

On 2026-10-21 all **19 MeetupNostr tests passed in debug and release** under
Swift 6.2 / Linux x86_64. The full package debug suite also passed (113 existing
XCTest tests, one live-calendar fixture test skipped because
`MEETUP_LIVE_ICS_FIXTURE` was unset). Source checkout HEAD and the package's
single resolved pin matched the revision above. Apple/Xcode builds are a
separate integration check; Linux results do not establish them.

From the repository root with a supported toolchain and an already-resolved
checkout, the package checks are:

```sh
swift test --package-path apple-native/Packages/MeetupCore --disable-automatic-resolution
swift test -c release --package-path apple-native/Packages/MeetupCore --disable-automatic-resolution --filter MeetupNostrTests
```

The final local run used scratch path
`/tmp/einundzwanzig-meetup-nostr-final/build`, with the PoC's resolved source
checkout copied there, and cache/config/security/module-cache paths under
`/tmp/einundzwanzig-secp256k1-poc`. It used the restored Swift 6.2
toolchain/runtime under `/tmp`. Full debug and targeted release logs are
`/tmp/einundzwanzig-meetup-nostr-debug-tests.log` and
`/tmp/einundzwanzig-meetup-nostr-release-tests.log`. No compiled provider artifact
is vendored in this repository.

Existing Dart regressions to consult are `test/relay_event_signature_test.dart:17–74`
(runtime-generated calendar event; rejects tampered tags, pubkey, ID, and
signature), and `test/nostr_profile_lookup_test.dart:12–27` (public test scalar 1,
fixed clock, signed metadata fixture factory). They are useful interoperability
inputs, not substitutes for independently published expected hashes. No fixed
NIP-01 hash fixture was found in those files. `test/fixtures/nip44.vectors.json`
tests a different protocol.

[provider-manifest]: https://github.com/21-DOT-DEV/swift-secp256k1/blob/e70a10e036a55fffea31568f0af92d69b6d449cd/Package.swift
[c-version]: https://github.com/21-DOT-DEV/swift-secp256k1/blob/e70a10e036a55fffea31568f0af92d69b6d449cd/Vendor/secp256k1/CMakeLists.txt#L6-L10
[getting-started]: https://github.com/21-DOT-DEV/swift-secp256k1/blob/e70a10e036a55fffea31568f0af92d69b6d449cd/Sources/P256K/P256K.docc/GettingStarted.md#L13
[resolution]: https://github.com/swiftlang/swift-package-manager/blob/main/Sources/PackageManagerDocs/Documentation.docc/ResolvingPackageVersions.md
[plugin]: https://github.com/21-DOT-DEV/swift-secp256k1/blob/e70a10e036a55fffea31568f0af92d69b6d449cd/Plugins/SharedSourcesPlugin/Plugin.swift#L19-L42
[prebuild]: https://github.com/swiftlang/swift-evolution/blob/main/proposals/0303-swiftpm-extensible-build-tools.md#prebuild-commands
[xcode-trust]: https://github.com/realm/SwiftLint#xcode-projects
[swiftpm-sandbox]: https://github.com/swiftlang/swift-package-manager/blob/swift-6.2-RELEASE/Sources/Basics/Sandbox.swift#L36-L67
[sha256]: https://github.com/21-DOT-DEV/swift-secp256k1/blob/e70a10e036a55fffea31568f0af92d69b6d449cd/Sources/Shared/SHA256.swift#L60-L66
[utility]: https://github.com/21-DOT-DEV/swift-secp256k1/blob/e70a10e036a55fffea31568f0af92d69b6d449cd/Sources/libsecp256k1/src/Utility.c#L26-L30
[nip01]: https://github.com/nostr-protocol/nips/blob/master/01.md#events-and-signatures
[schnorr]: https://github.com/21-DOT-DEV/swift-secp256k1/blob/e70a10e036a55fffea31568f0af92d69b6d449cd/Sources/Shared/Schnorr/Schnorr%2BSignature.swift
[digest]: https://github.com/21-DOT-DEV/swift-secp256k1/blob/e70a10e036a55fffea31568f0af92d69b6d449cd/Sources/Shared/HashDigest.swift#L45-L54
[xonly]: https://github.com/21-DOT-DEV/swift-secp256k1/blob/e70a10e036a55fffea31568f0af92d69b6d449cd/Sources/Shared/Keys/XonlyKeyImplementation.swift#L78-L85
[rng]: https://github.com/21-DOT-DEV/swift-secp256k1/blob/e70a10e036a55fffea31568f0af92d69b6d449cd/Sources/Shared/swift-crypto/Sources/Crypto/Util/BoringSSL/RNG_boring.swift#L20-L42
[system-rng]: https://github.com/swiftlang/swift/blob/main/stdlib/public/core/Random.swift#L116-L140
[secure-bytes]: https://github.com/21-DOT-DEV/swift-secp256k1/blob/e70a10e036a55fffea31568f0af92d69b6d449cd/Sources/Shared/swift-crypto/Sources/Crypto/Util/SecureBytes.swift#L331-L337
[zeroization]: https://github.com/21-DOT-DEV/swift-secp256k1/blob/e70a10e036a55fffea31568f0af92d69b6d449cd/Sources/Shared/Zeroization.swift#L31-L48
[nip59-vector]: https://github.com/nostr-protocol/nips/blob/master/59.md#1-create-an-event
[bip340-vectors]: https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv
