# :data:trust

Pure trust scoring without UI, I/O or storage: the local Trust Score v3 (bootstrap phases,
promotion check, bridge score, sybil heuristics), the four-layer evaluation of the reputation
widget, the trust-level mapping, and the pure parts of the relay reputation content (basic
score, relay level, change hash). Spec: `spec/04-trust-reputation.md` §2.0, §3.2–§3.6, §7.4,
§8.1–§8.5, §8.9. Dart sources: `lib/services/trust_score_service.dart`,
`lib/widgets/reputation_layers_widget.dart` (scores and selection only),
`lib/services/reputation_publisher.dart` (`_calculateBasicScore`, `_scoreToLevel`,
`_computeContentHash`).

```kotlin
implementation(project(":data:trust"))
```

`api` dependency: `:data:badges` (`MeetupBadge`, and through it `:core:common` with
`DartDateTime`/`TimeSource`).

Package root: `space.einundzwanzig.meetup.data.trust`

## Score — `…score`

| API | Purpose |
|---|---|
| `interface TrustScoreCalculator` (inject, `@Singleton`) | `calculate(badges, firstBadgeDate: DartDateTime?, coAttestors: Map<String, CoAttestorData>? = null): TrustScore` is Dart `calculateScore`. `calculate(badges)` passes `earliestDate(badges)`, which is what every Flutter caller (home, QR, card, `AdminStatusVerifier`) does. Also `bridgeScore(badges)` and `suspiciousPatterns(badges, coAttestors)`, both unused in production |
| `TrustScoreCalculator.coAttestorKey(badge, zone)` | Key of the `coAttestors` map (Dart `_badgeToEventId`): `DartString.lower(meetupName).replace(' ', '-') + '-' + yyyy-MM-dd` in `zone`. Producers of `CoAttestorData` maps must use it with the calculator's zone (`ZoneIdProvider.zone()`); a mismatching key silently gives no bonus |
| `TrustScoreCalculator.earliestDate(badges)` | First date after a Dart `List.sort` by date over **all** badges (markers and legacy included), or `null` |
| `TrustScoreCalculator.create(time, zone = ZoneIdProvider.SYSTEM, log = AppLogger)` | Factory for tests and code that does not use Hilt |
| `TrustScoreCalculator.PROGRESS_KEYS`, `REASON_ALL_MET`, `REASON_EMPTY` | `badges, meetups, signers, age, score` (this order matters); the German reason strings |
| `data class TrustScore` | `totalScore, maturityScore, diversityScore, qualityScore, activityScore, totalBadges, uniqueMeetups, uniqueSigners, uniqueCities, accountAgeDays, meetsPromotionThreshold, promotionReason, badgeValues, currentPhase, activeThresholds, progress` (insertion-ordered). Derived: `displayScore` (0..10), `level` (wire string, local 3/10/20/40 scale), `trustLevel`, `levelEmoji`, `promotionProgress` |
| `data class PromotionProgress(label, current, required)` | `met`, `percentage` |
| `data class BadgeValue`, `data class CoAttestorData` | As in Dart |
| `enum BootstrapPhase { KEIMPHASE, WACHSTUM, STABIL }`, `data class PhaseThresholds` | Phases and their thresholds |
| `object TrustConfig` | `phases`, `thresholds(phase)`, `WEIGHT_*`, `HALF_LIFE_WEEKS` (26.0), `MAX_BADGES_PER_WEEK` (2) |

`calculate` never throws. Flutter's `round()` throws on a non-finite `totalScore` (badges dated
centuries ahead, from a crafted wallet or import); here the `"score"` progress saturates to
`Int.MAX_VALUE`/`Int.MIN_VALUE` (NaN → 0), and finite values above the `Int` range clamp instead
of wrapping (Dart int64 keeps the sign, so `met` matches Flutter). `totalScore` itself may be
±Infinity or NaN; `level`/`displayScore` handle that.

Organizer promotion (`:data:admin`) reads `calculate(badges).meetsPromotionThreshold` and
`activeThresholds.promotionScore`. Show the score with `totalScore.toStringAsFixed(1)` from
`:core:common`.

`ZoneIdProvider` (root package) is the device zone. Hilt provides `ZoneIdProvider.SYSTEM`,
which reads `ZoneId.systemDefault()` on every call. Tests pass a fixed zone.

## Layers — `…layers`

`object TrustLayersEvaluator` is the pure part of `ReputationLayersWidget` (spec 04 §8.9):

| API | Purpose |
|---|---|
| `evaluate(LayersInput): LayersEvaluation` | All scores, `activeLayerCount`, `combinedScore`, `signal`, the `has*Data` flags of the four cards, the meaning of each card (`MeetupMeaning`, `LightningMeaning`, `SocialMeaning`, `IdentityMeaning`), `hasSignerDiversity`, `allBadgesBound` |
| `physicalScore(...)`, `identityScore(...)`, `activeLayerCount(in)`, `combinedScore(in)`, `signal(in)`, `signalFor(layers, score)` | The individual formulas |
| `enum TrustSignalKind` | `WEAK, LIMITED, BUILDING, CONNECTED, SOLID, DEFAULT`, each with `labelKey`/`explanationKey`/`adviceKey` (ARB keys). The KDoc of each value names the Flutter colour and icon |
| `LayersInput(badgeCount, boundBadges, meetupCount, signerCount, since, zaps, humanityVerified, social, nip05, platformProofCount, platformProofs: Map<platform, username>, accountAgeDays)` | The widget's constructor parameters |
| `platformLabel(key)` | Dart `_platformLabel` (`telegram` → `Telegram`, … `other` → `Andere`, else the raw key), label of the platform rows |
| `MeetupMeaning.NO_TEXT` | Data present but Flutter shows no meaning line (`null`); happens for a negative remote badge count without signer diversity |
| `ZapLayerInput(...)` (`totalCount`, `lightningScore`, `activityLabel`), `SocialLayerInput(...)` (`socialScore`), `Nip05LayerInput(valid, nip05, domainKind)` (`score`, `domainLabel`), `enum Nip05DomainKind` | Minimal input types. The zap, social-graph and NIP-05 domains map their own types to these. The score getters are the Dart getters verbatim, so those domains can reuse them instead of keeping a second copy |

The UI builds the detail rows (`rlBadgeCount`, `rlZapsSent`, …) from `LayersInput` itself.
They are plain conditionals (spec 04 §8.9). Use `zaps.activityLabel` as sublabel of the
`rlMonthsActive` row, `nip05.domainLabel` for the NIP-05 row and `platformLabel(key)` for the
platform rows (all German, not localized in Flutter either). Social details: render when
`input.social != null`, independent of `hasSocialData`; identity details are always rendered.

## Levels — `…level`

| API | Purpose |
|---|---|
| `enum TrustLevel(wire, labelKey)` | `NEU, STARTER, AKTIV, ETABLIERT, VETERAN`, with ARB keys `levelNew`…`levelVeteran`. `fromWire(s)`, `forLocalScore(total)` (3/10/20/40), `forBasicScore(score)` (>0/2/5/8, relay). Never unify the two scales (R-3) |
| `data class TrustLevelStyle(icon: TrustLevelIcon, color: TrustLevelColor, argb: Long)` | Spec §2.0 table (QR card, scanner). Render `color` via theme tokens; `argb` is only the Flutter reference. `forWire(value)` (unknown, `""` and `NEU` all map to `OTHER`), `labelKeyForWire(value)` (`null` means the UI shows the raw value) |
| `enum TrustLevelColor` | `AMBER, SUCCESS_GREEN` (→ `extendedColors.success`), `COMMUNITY_GOLD` (→ `community`), `BITCOIN_ORANGE` (→ `bitcoin`), `NEUTRAL_GREY` |
| `enum TrustLevelIcon` | `BOLT, SHIELD, LOCAL_FIRE_DEPARTMENT, ECO, FIBER_NEW` (Flutter `Icons.*` names) |

Home uses the same icons but falls back to `textTertiary` (Flutter `cTextTertiary` 0xFF868A94)
instead of grey for NEU/unknown, and also while no score is loaded yet (icon `FIBER_NEW`).

## Relay reputation math — `…reputation`

`object RelayReputationMath` covers the pure parts of `ReputationPublisher`, for `:data:reputation`:

| API | Purpose |
|---|---|
| `basicScore(totalBadges, boundBadges, uniqueMeetups, uniqueSigners, accountAgeDays)` | `_calculateBasicScore` (0..10) |
| `publishedScore(basic)` | `double.parse(score.toStringAsFixed(1))`, the value of `stats.score` |
| `levelForBasic(score)` | `_scoreToLevel`, the value of `stats.level` |
| `changeHash(badges)` / `changeHash(total, bound, meetups, signers)` | `_computeContentHash`: `sha256("t\|b\|m\|s")[0:16]`, for prefs `reputation_last_hash` |

## Tests

```
tools/gradlew-locked :data:trust:testDebugUnitTest
```

There are 66 JVM tests:
- `TrustScoreTest` is a 1:1 port of `test/trust_score_test.dart` (26 cases) with a fixed clock.
- `TrustScoreRegressionTest` ports the score part of `badge_reputation_regression_test.dart`: real BIP-340 signed badges and an in-memory `BadgeRepository` restore.
- `TrustScoreScenarioTest` checks the fixture sections `trustScoreScenarios` (S1–S3, every field and every `BadgeValue`), `trustScoreEmpty` and `levelThresholdsLocal`.
- `TrustScoreEdgeCaseTest` covers what the Dart suite does not: far-future badges (non-finite and > `Int` totals), the zone-dependent week cap (Europe/Berlin vs UTC), `coAttestorKey`, the co-attestor bonus path and sybil check 3.
- `RelayReputationMathTest` checks `relayBasicScore`, `publishContentHash16` and `toStringAsFixed1`.
- `TrustLayersEvaluatorTest` checks the L1–L3 vectors of §10.4, the selection tables and the detail-row labels.
- `TrustLevelTest` checks the level mapping.

The tests read `spec/fixtures/04-trust-reputation-vectors.json` directly.
