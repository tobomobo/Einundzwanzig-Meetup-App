package space.einundzwanzig.meetup.data.identity.passkey

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.DomError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException

/**
 * Credential Manager failures → [PasskeyPrfException] codes, mirroring what the Flutter app made
 * of them (passkeys_android `MessageHandler` + `identity_setup_screen.dart` `tPasskeyError`):
 * cancellations stay silent; a missing asset-links association (RP id not validated), a missing
 * or outdated provider and every error the plugin reported as `android-unhandled` read as
 * "passkey unavailable"; the few errors the plugin named explicitly (timeout, no Google account,
 * sync account, excluded credential) keep the platform text ([PasskeyPrfException.FAILED]).
 */
internal object PasskeyErrors {

    /** Platform texts that mean "this app is not associated with the RP id" (missing assetlinks.json). */
    private val DOMAIN_HINTS = listOf("rp id", "asset", "validated", "domain", "not associated")

    /** Messages the Flutter plugin treated as a cancellation (skipped fingerprint animation). */
    private const val CREATE_SKIPPED = "Unable to create key during registration"
    private const val GET_SKIPPED = "None of the allowed credentials can be authenticated"

    /** DOM messages the Flutter plugin mapped to named (raw-text) errors instead of `android-unhandled`. */
    private val NAMED_CREATE_MESSAGES = setOf("User is unable to create passkeys.", "Unable to get sync account.", "[15] Flow has timed out.")
    private val NAMED_GET_MESSAGES = setOf("Failed to decrypt credential.", "[15] Flow has timed out.")

    fun create(e: CreateCredentialException): PasskeyPrfException {
        val text = e.errorMessage?.toString()
        val code = when {
            e is CreateCredentialCancellationException || text == CREATE_SKIPPED -> PasskeyPrfException.CANCELLED
            e is CreateCredentialNoCreateOptionException -> PasskeyPrfException.NO_CREATE_OPTION
            e is CreateCredentialUnsupportedException -> PasskeyPrfException.UNSUPPORTED
            e is CreateCredentialProviderConfigurationException -> PasskeyPrfException.UNSUPPORTED
            e is CreatePublicKeyCredentialDomException -> dom(
                e.domError,
                text,
                named = text in NAMED_CREATE_MESSAGES || text?.lowercase()?.contains("excluded credentials") == true,
            )
            e is CreateCredentialUnknownException && isDomainHint(text) -> PasskeyPrfException.DOMAIN_NOT_ASSOCIATED
            else -> PasskeyPrfException.UNHANDLED
        }
        return build(code, text, e.type, e)
    }

    fun get(e: GetCredentialException): PasskeyPrfException {
        val text = e.errorMessage?.toString()
        val code = when {
            e is GetCredentialCancellationException || text == GET_SKIPPED -> PasskeyPrfException.CANCELLED
            e is NoCredentialException -> PasskeyPrfException.NO_CREDENTIAL
            e is GetCredentialUnsupportedException -> PasskeyPrfException.UNSUPPORTED
            e is GetCredentialProviderConfigurationException -> PasskeyPrfException.UNSUPPORTED
            e is GetPublicKeyCredentialDomException -> dom(e.domError, text, named = text in NAMED_GET_MESSAGES)
            e is GetCredentialUnknownException && isDomainHint(text) -> PasskeyPrfException.DOMAIN_NOT_ASSOCIATED
            else -> PasskeyPrfException.UNHANDLED
        }
        return build(code, text, e.type, e)
    }

    private fun dom(error: DomError, text: String?, named: Boolean): String = when {
        // Missing / wrong assetlinks.json for the RP id; checked before NotAllowedError, which
        // Credential Manager also uses for "RP ID cannot be validated" (must not read as a silent cancel).
        error is SecurityError -> PasskeyPrfException.DOMAIN_NOT_ASSOCIATED
        isDomainHint(text) -> PasskeyPrfException.DOMAIN_NOT_ASSOCIATED
        error is NotSupportedError -> PasskeyPrfException.UNSUPPORTED
        error is NotAllowedError -> PasskeyPrfException.CANCELLED
        named -> PasskeyPrfException.FAILED
        else -> PasskeyPrfException.UNHANDLED
    }

    private fun isDomainHint(text: String?): Boolean {
        val lower = text?.lowercase() ?: return false
        return DOMAIN_HINTS.any { it in lower }
    }

    private fun build(code: String, text: String?, type: String, cause: Exception): PasskeyPrfException {
        val message = when (code) {
            PasskeyPrfException.FAILED -> text ?: type
            PasskeyPrfException.UNHANDLED -> "$code: $type"
            else -> code
        }
        return PasskeyPrfException(code, message, cause)
    }
}
