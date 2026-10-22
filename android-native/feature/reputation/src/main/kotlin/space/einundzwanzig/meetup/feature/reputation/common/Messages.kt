package space.einundzwanzig.meetup.feature.reputation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import space.einundzwanzig.meetup.data.proofs.platform.VerifyMessage
import space.einundzwanzig.meetup.feature.reputation.R

/**
 * Resource of a German QR verification message of `BadgeSecurity.verifyQrV3` /
 * `verifyQrLegacy` (spec 04 §2.10 `qrVerify*`), or `null` for an unknown text (shown raw).
 * `Fehler: Invalid event` is how Flutter reports a bad Schnorr signature (§7.7 step 4).
 */
internal fun qrVerifyMessageRes(german: String): Int? = when (german) {
    "Schnorr-Signatur verifiziert ✓" -> R.string.native_qrVerifyOk
    "Fehlende Signatur-Daten" -> R.string.native_qrVerifyMissing
    "Ungültiges Pubkey-Format" -> R.string.native_qrVerifyBadPubkey
    "Ungültiges Signatur-Format" -> R.string.native_qrVerifyBadSig
    "Fehler: Invalid event", "Schnorr-Signatur UNGÜLTIG!" -> R.string.native_qrVerifyInvalid
    "Legacy-QR (v1) wird nicht mehr akzeptiert. Bitte Organisator um neuen v2-Tag." -> R.string.native_qrVerifyLegacy
    else -> null
}

/** Localized QR verification message. */
@Composable
internal fun qrVerifyMessage(german: String): String = qrVerifyMessageRes(german)?.let { stringResource(it) } ?: german

/** Localized verify-string message (spec 04 §8.10, `rvMsg*` of §2.10). */
@Composable
internal fun verifyMessage(message: VerifyMessage): String = when (message) {
    VerifyMessage.BadFormat -> stringResource(R.string.native_rvMsgBadFormat)
    VerifyMessage.BadSignature -> stringResource(R.string.native_rvMsgBadSignature)
    is VerifyMessage.UsernameMismatch -> stringResource(R.string.native_rvMsgUsernameMismatch, message.claimed, message.expected)
    VerifyMessage.NoReputation -> stringResource(R.string.native_rvMsgNoReputation)
    VerifyMessage.Full -> stringResource(R.string.native_rvMsgFull)
    is VerifyMessage.PartialWithProof -> stringResource(R.string.native_rvMsgPartialWithProof, message.bound, message.total)
    VerifyMessage.PartialNoProof -> stringResource(R.string.native_rvMsgPartialNoProof)
    VerifyMessage.SignatureOnlyNoBindings -> stringResource(R.string.native_rvMsgSignatureOnlyNoBindings)
    VerifyMessage.NpubNoReputation -> stringResource(R.string.native_rvMsgNpubNoReputation)
    VerifyMessage.NpubWithBindings -> stringResource(R.string.native_rvMsgNpubWithBindings)
    VerifyMessage.NpubNoBindings -> stringResource(R.string.native_rvMsgNpubNoBindings)
    is VerifyMessage.Error -> stringResource(R.string.native_rvMsgError, message.detail)
}
