package space.einundzwanzig.meetup.data.issuance.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.admin.model.AdminCheckResult
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaimService
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.badges.security.MeetupKey
import space.einundzwanzig.meetup.data.badges.security.VerifyResult
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeChainService
import space.einundzwanzig.meetup.data.issuance.event.EventChainStatus
import space.einundzwanzig.meetup.data.issuance.nfc.NfcBadgePayload
import space.einundzwanzig.meetup.data.issuance.nfc.NfcPayloadError
import space.einundzwanzig.meetup.data.issuance.scan.AdminCheckInfo
import space.einundzwanzig.meetup.data.issuance.scan.BadgeQrParser
import space.einundzwanzig.meetup.data.issuance.scan.BadgeScanProcessor
import space.einundzwanzig.meetup.data.issuance.scan.ClaimInfo
import space.einundzwanzig.meetup.data.issuance.scan.FoundInfo
import space.einundzwanzig.meetup.data.issuance.scan.PendingBadge
import space.einundzwanzig.meetup.data.issuance.scan.QrDetection
import space.einundzwanzig.meetup.data.issuance.scan.ScanOutcome
import space.einundzwanzig.meetup.data.issuance.session.NonceStatus
import space.einundzwanzig.meetup.data.issuance.session.RollingQr
import javax.inject.Inject
import javax.inject.Singleton

/** Port of the non-UI part of `lib/screens/meetup_verification.dart` (spec 02 §2.1). */
@Singleton
internal class DefaultBadgeScanProcessor @Inject constructor(
    private val repository: BadgeRepository,
    private val claims: BadgeClaimService,
    private val chain: EventBadgeChainService,
    private val admins: AdminPort,
    private val directory: MeetupDirectoryPort,
    private val identity: IdentityPort,
    private val blockHeights: BlockHeightPort,
    private val time: TimeSource,
    private val zones: ZoneSource,
) : BadgeScanProcessor {

    internal var log: DiagnosticLog = AppLogger

    override suspend fun processQrText(raw: String, unknownMeetupLabel: String): ScanOutcome? =
        when (val detection = BadgeQrParser.parse(raw)) {
            null -> null
            QrDetection.ReviewDemo -> ScanOutcome.ReviewDemo
            is QrDetection.Badge -> processQr(detection.payload, unknownMeetupLabel)
        }

    override suspend fun processQr(scanned: JsonObject, unknownMeetupLabel: String): ScanOutcome {
        // Rolling QR freshness (screenshots expire after ~20 s).
        if (RollingQr.hasNonce(scanned)) {
            val nonce = RollingQr.validateNonce(scanned, time.nowSeconds())
            if (!nonce.isValid) {
                log.warn(TAG, "Rolling-QR abgelehnt: ${nonce.message}")
                // Wrongly typed n/ts is a format error, not an expired code.
                return if (nonce.status == NonceStatus.MALFORMED) {
                    ScanOutcome.Malformed(nonce.message, rollingQrFormat = true)
                } else {
                    ScanOutcome.NonceRejected(nonce)
                }
            }
        }
        // The rolling fields are appended after signing and must not enter the hash.
        val toVerify = LinkedHashMap(scanned).apply {
            remove("n"); remove("ts"); remove("d"); remove("qr_nonce"); remove("qr_time_step")
        }
        val result = BadgeSecurity.verify(JsonObject(toVerify), time.nowSeconds())
        if (!result.isValid) {
            log.warn(TAG, "Signaturpruefung fehlgeschlagen (Version ${result.version}): ${result.message}")
            return ScanOutcome.VerificationFailed(result)
        }
        val tagData = LinkedHashMap<String, JsonElement>(scanned)
        if (result.version >= 2 && result.adminNpub.isNotEmpty()) {
            tagData["_verified_by"] = JsonPrimitive(Nip19.shortenNpub(result.adminNpub))
        }
        if ("delivery" !in tagData) tagData["delivery"] = JsonPrimitive(RollingQr.DELIVERY_ROLLING_QR)
        return processFoundTagData(tagData, result, unknownMeetupLabel)
    }

    override suspend fun processNfcPayload(recordPayload: ByteArray, unknownMeetupLabel: String): ScanOutcome {
        val text = when (val decoded = NfcBadgePayload.decodeTextPayload(recordPayload)) {
            is NfcBadgePayload.Result.Error -> return ScanOutcome.NfcRejected(decoded.error)
            is NfcBadgePayload.Result.Text -> decoded.text
        }
        val tagObject = try {
            DartJson.decode(text) as? JsonObject
                ?: return ScanOutcome.NfcRejected(NfcPayloadError.InvalidTag("Kein JSON-Objekt"))
        } catch (e: Exception) {
            log.error(TAG, "Tag-Inhalt nicht auswertbar (kein gueltiges JSON?)", e)
            return ScanOutcome.NfcRejected(NfcPayloadError.InvalidTag(e.message ?: e.toString()))
        }
        val result = BadgeSecurity.verify(tagObject, time.nowSeconds())
        log.info(
            TAG,
            "NFC-Signaturpruefung: ${if (result.isValid) "gueltig" else "UNGUELTIG"} (Version ${result.version})" +
                if (result.isValid) "" else " — ${result.message}",
        )
        if (!result.isValid) return ScanOutcome.VerificationFailed(result)
        val tagData = LinkedHashMap<String, JsonElement>(tagObject)
        if (result.version >= 2 && result.adminNpub.isNotEmpty()) {
            tagData["_verified_by"] = JsonPrimitive(Nip19.shortenNpub(result.adminNpub))
        }
        tagData["delivery"] = JsonPrimitive("nfc")
        return processFoundTagData(tagData, result, unknownMeetupLabel)
    }

    /** Dart `_processFoundTagData`, in the exact order of spec 02 §2.1. */
    private suspend fun processFoundTagData(
        tagData: LinkedHashMap<String, JsonElement>,
        verifyResult: VerifyResult,
        unknownMeetupLabel: String,
    ): ScanOutcome {
        val zone = zones.zone()
        val normalized = try {
            BadgeSecurity.normalize(JsonObject(tagData), zone)
        } catch (e: Exception) {
            log.warn(TAG, "Payload nicht normalisierbar", e)
            return ScanOutcome.Malformed(e.message ?: e.toString())
        }

        // 2–4: display name (portal city if known) and the SIGNED slug as identity basis.
        val meetupSlug = normalized.str("meetup_slug") ?: ""
        var meetupName = normalized.str("meetup_name") ?: unknownMeetupLabel
        var meetupCountry = normalized.str("meetup_country") ?: ""
        if (meetupSlug.isNotEmpty()) {
            directory.bySlug(meetupSlug, meetupCountry)?.let { portal ->
                meetupName = portal.city
                if (meetupCountry.isEmpty()) meetupCountry = portal.country
            }
        }
        val keySlug = meetupSlug.ifEmpty { MeetupKey.slug(meetupName) }
        val meetupId = normalized.str("meetup_id") ?: DartDateTime.now(time).toDartString(zone)

        // 6: fresh block height (one retry), else the tag's b.
        var blockHeight = blockHeights.blockHeight()
        if (blockHeight == 0L) {
            delay(BLOCK_HEIGHT_RETRY_MS)
            blockHeight = blockHeights.blockHeight()
        }
        if (blockHeight == 0L) blockHeight = normalized["block_height"].dartIntOrNull() ?: 0L

        // 7–8: duplicate check against the STORED wallet (organizer markers excluded).
        val fullName = if (meetupCountry.isNotEmpty()) "$meetupName, $meetupCountry" else meetupName
        val stored = repository.loadBadges()
        val now = DartDateTime.now(time)
        val today = now.fields(zone)
        val todayStr = now.toIso8601String(zone).substring(0, 10)
        val prospectiveEventId = "$keySlug-$todayStr"
        val collectible = stored.filter { !it.isOrganizer }
        val scanEventAddress = BadgeSecurity.eventAddressOf(normalized)
        val alreadyCollected = if (scanEventAddress != null) {
            // One badge per EVENT (any day); older event badges matched by title.
            val eventKey = BadgeSecurity.eventBadgeKey(scanEventAddress)
            collectible.any { it.meetupEventId == eventKey || (it.isEvent && it.meetupName == fullName) }
        } else {
            collectible.any { b ->
                (b.meetupEventId.isNotEmpty() && b.meetupEventId == prospectiveEventId) ||
                    (b.meetupName == fullName && b.date.fields(zone).let {
                        it.year == today.year && it.monthValue == today.monthValue && it.dayOfMonth == today.dayOfMonth
                    })
            }
        }
        if (alreadyCollected) log.diag(TAG, "Bereits gesammelt: \"$fullName\" ($prospectiveEventId) — Scan abgelehnt.")

        // 9: self-scan prevention (fail-open on lookup errors).
        val adminPubkeyForSelfCheck = normalized.str("admin_pubkey") ?: tagData.str("p") ?: ""
        if (adminPubkeyForSelfCheck.isNotEmpty() && isSelfScan(normalized, adminPubkeyForSelfCheck)) {
            log.warn(TAG, "Selbst-Scan abgelehnt.")
            return ScanOutcome.SelfScan
        }

        if (alreadyCollected) return ScanOutcome.AlreadyCollected(fullName, isEvent = scanEventAddress != null)

        // 10: the new badge.
        val signerNpub = normalized.str("admin_npub") ?: ""
        val delivery = normalized.str("delivery") ?: tagData.str("delivery") ?: "nfc"
        // Only a real name gives an id; otherwise no co-attendance key (a shared "-<date>" id once
        // linked strangers worldwide).
        val unknownSlug = DartString.lower(DartString.trim(unknownMeetupLabel)).replace(" ", "-")
        val usableName = keySlug.isNotEmpty() && keySlug != unknownSlug && keySlug.replace("-", "").isNotEmpty()
        val meetupEventId = when {
            scanEventAddress != null -> BadgeSecurity.eventBadgeKey(scanEventAddress)
            usableName -> "$keySlug-$todayStr"
            else -> ""
        }
        if (!usableName) {
            log.warn(TAG, "Tag ohne verwertbaren Meetup-Namen — keine Kennung vergeben, Anwesenheit wird nicht veroeffentlicht.")
        }

        val sig = normalized.str("sig") ?: tagData.str("s") ?: ""
        val sigId = normalized.str("sig_id") ?: ""
        val adminPubkey = normalized.str("admin_pubkey") ?: tagData.str("p") ?: ""
        val sigVersion = if (sig.length == 128) 2L else if (sig.isNotEmpty()) 1L else 0L

        var isKnownAdmin = false
        var adminCheck: AdminCheckInfo = AdminCheckInfo.None
        var eventBadgeImage = ""
        if (verifyResult.version >= 2 && adminPubkey.isNotEmpty()) {
            if (scanEventAddress != null) {
                // The scanner's clock, not the helper's: a removed helper cannot backdate.
                val result = chain.verify(scanEventAddress, adminPubkey, issuedAt = time.nowSeconds())
                isKnownAdmin = result.ok
                eventBadgeImage = result.badgeImageUrl
                adminCheck = when (result.status) {
                    EventChainStatus.VERIFIED -> AdminCheckInfo.EventIssuerOk(result.eventTitle, result.creatorName)
                    EventChainStatus.SIGNER_NOT_LISTED -> AdminCheckInfo.EventSignerNotListed(result.eventTitle)
                    EventChainStatus.CREATOR_NOT_AUTHORIZED -> AdminCheckInfo.EventCreatorNotAuthorized(result.eventTitle)
                    EventChainStatus.EVENT_HAS_NO_BADGE -> AdminCheckInfo.EventHasNoBadge(result.eventTitle)
                    EventChainStatus.EVENT_NOT_FOUND -> AdminCheckInfo.EventNotFound
                }
            } else {
                val recognition = recognizeSigner(adminPubkey, meetupName)
                isKnownAdmin = recognition.first
                adminCheck = recognition.second
            }
        } else if (verifyResult.version == 1) {
            adminCheck = AdminCheckInfo.LegacyBadge
        }

        // Full scanned map (incl. unsigned add-ons and delivery) for later re-verification.
        val sigContent = DartJson.encode(JsonObject(LinkedHashMap(tagData).apply { remove("_verified_by") }))

        // Automatic claim binding (kind 21002) to the collector.
        var claim: ClaimInfo = ClaimInfo.None
        var claimSig = ""
        var claimEventId = ""
        var claimPubkey = ""
        var claimTimestamp = 0L
        if (sig.isNotEmpty() && sigVersion >= 2) {
            val result = claims.createClaim(orgSig = sig, orgEventId = sigId, orgPubkey = adminPubkey, blockHeight = blockHeight)
            if (result.success) {
                claimSig = result.claimSig
                claimEventId = result.claimEventId
                claimPubkey = result.claimPubkey
                claimTimestamp = result.claimTimestamp
                claim = ClaimInfo.Bound
            } else {
                claim = ClaimInfo.Failed(result.message, result.reason)
            }
        }

        // Only SIGNED coordinates count as a measured reference (Audit H1): always 0/0 today.
        val (orgLat, orgLng) = BadgeSecurity.signedCoordinates(JsonObject(tagData))
        val badge = MeetupBadge(
            id = meetupId,
            meetupName = fullName,
            date = DartDateTime.now(time),
            iconPath = ICON_PATH,
            blockHeight = blockHeight,
            signerNpub = signerNpub,
            meetupEventId = meetupEventId,
            delivery = delivery,
            sig = sig,
            sigId = sigId,
            adminPubkey = adminPubkey,
            sigVersion = sigVersion,
            sigContent = sigContent,
            claimSig = claimSig,
            claimEventId = claimEventId,
            claimPubkey = claimPubkey,
            claimTimestamp = claimTimestamp,
            isRetroactive = false,
            isEvent = scanEventAddress != null,
            coverUrl = eventBadgeImage,
        )
        val info = FoundInfo(
            fullName = fullName,
            blockHeight = blockHeight,
            verifiedBy = tagData.str("_verified_by"),
            sigVersion = sigVersion,
            claim = claim,
            adminCheck = adminCheck,
            expiry = BadgeSecurity.expiryInfo(JsonObject(tagData), time.nowSeconds()),
            unknownSigner = !isKnownAdmin && verifyResult.version >= 2 && adminPubkey.isNotEmpty(),
        )
        log.diag(TAG, "Badge gefunden: \"$fullName\" · Zustellung=$delivery · sigVersion=$sigVersion · bekannt=$isKnownAdmin")
        return ScanOutcome.Found(PendingBadge(badge, orgLat, orgLng), info)
    }

    /** Self-scan: own npub equals the signer npub, or the decoded own npub equals the hex pubkey. */
    private suspend fun isSelfScan(normalized: JsonObject, adminPubkey: String): Boolean = try {
        val myNpub = identity.npub()
        val signerNpub = normalized.str("admin_npub") ?: ""
        var self = myNpub != null && signerNpub.isNotEmpty() && myNpub == signerNpub
        if (!self && myNpub != null) {
            self = try {
                Nip19.decodeNpub(myNpub) == adminPubkey
            } catch (_: Exception) {
                false
            }
        }
        self
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Fail-open: rather one badge too many than a frustrated participant.
        log.debug(TAG, "Selbst-Scan-Pruefung fehlgeschlagen: ${e.message}")
        false
    }

    /**
     * Meetup signer recognition (spec 02 §8.7): registry → `unavailable` → portal leaders of the
     * meetup resolved from the DISPLAY name → portal meetup whose `nostr` is the signer → unknown.
     * A flat "leader anywhere" check is forbidden (anyone can create a portal meetup).
     */
    private suspend fun recognizeSigner(adminPubkey: String, meetupName: String): Pair<Boolean, AdminCheckInfo> = try {
        val admin = admins.checkAdminByPubkey(adminPubkey)
        when {
            admin.isAdmin -> true to AdminCheckInfo.KnownOrganizer(admin.name ?: admin.meetup)
            admin.source == AdminCheckResult.SOURCE_UNAVAILABLE -> false to AdminCheckInfo.CheckFailed
            else -> portalRecognition(adminPubkey, meetupName)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(TAG, "Organisator-Pruefung fehlgeschlagen", e)
        false to AdminCheckInfo.CheckFailed
    }

    private suspend fun portalRecognition(adminPubkey: String, meetupName: String): Pair<Boolean, AdminCheckInfo> {
        var info: AdminCheckInfo = AdminCheckInfo.None
        val ownMeetup = directory.resolveFavorite(meetupName) ?: directory.byName(meetupName)
        val portalId = ownMeetup?.id?.toIntOrNull()
        if (ownMeetup != null && portalId != null) {
            val signerNpub = try {
                Nip19.encodeNpub(adminPubkey)
            } catch (_: Exception) {
                ""
            }
            val leads = if (signerNpub.isEmpty()) null else directory.isLeaderOf(portalId, signerNpub)
            when (leads) {
                true -> return true to AdminCheckInfo.PortalOrganizer(ownMeetup.name.ifEmpty { ownMeetup.city })
                null -> info = AdminCheckInfo.CheckFailed // no information is not a "no"
                false -> Unit
            }
        }
        if (info != AdminCheckInfo.None) return false to info
        val portalMeetup = directory.organizerMeetupFor(adminPubkey)
        return if (portalMeetup != null) {
            true to AdminCheckInfo.PortalOrganizer(portalMeetup.name.ifEmpty { portalMeetup.city })
        } else {
            false to AdminCheckInfo.UnknownSigner
        }
    }

    private companion object {
        const val TAG = "Scan"
        const val ICON_PATH = "assets/badge_icon.png"
        const val BLOCK_HEIGHT_RETRY_MS = 1_000L
    }
}

/** Dart `map[key] as String?` read leniently: only a JSON string counts. */
internal fun Map<String, JsonElement>.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
