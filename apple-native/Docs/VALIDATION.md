# Validation evidence and Apple acceptance checklist

Implementation host: Linux, 2026-10-21. A temporary **official Swift 6.2 compiler** and its runtime libraries were installed entirely under `/tmp`. No macOS/iOS device access, Xcode or Apple SDK was available. These are portable and static results, not an Apple app launch or screenshot.

## Executed checks

- **MeetupCore: 23 XCTest tests pass** in Swift 6 with compiler warnings treated as errors. Includes safe URLs, UTC/offset/invalid calendar dates, numeric coordinate aliases, dotted/nested event fields, same-city groups, duplicate IDs, decimal/Int64 boundaries, RSS CDATA/namespaces/summary sanitization, bounded streaming bodies, HTTP failures and cancellation.
- **Real source decoder smoke checks:** all **323** meetup directory records, **43** portal events (including **30** missing ends), and **50** RSS news articles decode. This validates the October 21 downloaded responses; it does not prove future server availability. Full live snapshots are not shipped as demo data.
- **CalendarICS: 10 portable tests pass.** Covers CRLF envelope, TEXT escaping and injection attempts, control characters, stable UID encoding, UTF-8 folding/unfolding, 75-byte boundaries, UTC timestamps, invalid starts/ends and URL escaping.
- **Actual app LocalModels: 9 portable tests pass.** Covers compact source QR country field, dropped signature/claim fields, rejection of unknown/private-key fields and malformed/oversized payloads, metadata-only persistence, separate demo/live planning/favorite domains, disabled crypto capabilities and language-specific search text from actual translated resources. A test caught raw malformed-JSON errors; the parser now maps them to the normal import failure state.
- **Swift 6 syntax parsing passes for all app sources** with targets `arm64-apple-ios26.0`, `arm64-apple-ios26.0-simulator`, and `arm64-apple-macosx26.0`. Syntax checking includes device-only camera branches. It caught and corrected a conditional SwiftUI scene declaration. Imports and Apple API overloads are not resolved by this check.
- **Portable project validator:** OpenStep object graph/references, target source membership, local package linkage, Debug/Release settings for all three SDKs, shared scheme IDs, plist/ATS/entitlements, assets and exact 57-route matrix. Localized resources are checked for syntax, duplicates and equal keys/placeholders across DE/EN/ES. See the final validator output for the current key count.
- Independent Foundation `PropertyListSerialization` parsed the `.pbxproj` as OpenStep. Icons were checked for expected dimensions/opacity; regeneration from the repository asset is deterministic.
- AppStore, LocalModels and PreviewFixtures were also **typechecked against the real built MeetupCore module with minimal Linux-only UI/Keychain stubs** by the parent audit. This catches ordinary Foundation/Swift concurrency errors, not Apple API or SwiftUI correctness. Stubs are not included in the app.

Portable commands and all three `xcodebuild` commands are in [README](../README.md). CI is configured but was not triggered or observed here. There is no claim that Apple builds or the workflow have passed.

## First Mac / iPhone acceptance pass — outstanding

1. With Xcode 26, run all three unsigned builds: iOS Simulator, generic iOS device and native Mac. Verify local Swift package linking, SDK overloads, asset compilation, shared scheme, localized resources and no unexpected entitlements.
2. Run **My Mac** with local/ad-hoc signing and an iOS 26 simulator with no development team or secrets. Complete nickname onboarding, relaunch and edit profile; verify persistence and separate Flutter data container. Try explicit reset from onboarding Settings and after ordinary use.
3. Explore preview mode offline. Star sample groups, save a sample plan/note, switch to live and verify sample actions do not appear as real favorites/plans. Switch back and confirm sample plans remain marked Preview. Sample QR imports must never become trusted.
4. Load live directory/news/calendar. Search Wien, Berlin and a city containing multiple groups; star exact IDs and relaunch. Open maps and Apple Maps. Test network offline/failed endpoint/invalid server, retry and cached source labels.
5. Open an event. Change a Going/Maybe plan and note, relaunch, remove it. Confirm the UI says **local only** and makes no portal or Nostr RSVP request. Cancel calendar review; it must not say Saved. Save deliberately, then check actual Calendar. Export/import a Unicode multiline `.ics` file. Deny Mac calendar access and use file fallback.
6. On a supported physical iPhone test QR camera permission granted/denied/restricted, interruption/background and dismiss. Scan a rolling source QR; it should extract unverified metadata only. Confirm camera unavailable on simulator/Mac and manual/file input still works. Try invalid JSON, a large file, private-key-like data and unknown fields.
7. Verify Badges grouping/search/details/deletion and unverified/demo labels. Reputation must show unavailable score and zero trusted badges, with no signed QR, ownership claim or successful verification state.
8. Read a news excerpt; original link/share works, read mark persists. No WebView executes untrusted RSS HTML. Check bad/relative URLs remain bounded by decoder validation.
9. Refresh Bitcoin; change server while request is in flight, then reset during another request. Old snapshots must not populate under the new source/reset. Exact decimal conversion should retain sats, reject excessive precision and show price timestamp/estimate.
10. Change language DE/EN/ES and appearance; relaunch. Test system language, very long translated labels, maximum Accessibility Dynamic Type, VoiceOver focus/order, keyboard navigation, high contrast, Reduce Motion and Reduce Transparency. Home control row should adapt vertically and custom glass become opaque when requested.
11. On Mac verify sidebar selection, ⌘1…5, ⇧⌘S, ⌘R, Settings menu, file importer/exporter sandbox access and multiwindow state synchronization. Confirm no camera entitlement is requested on Mac.

Any Apple compile, runtime, privacy-permission or accessibility issue discovered in this pass must be fixed before distribution. Future security integrations need their own official vector suites and review before any unavailable state is enabled.
