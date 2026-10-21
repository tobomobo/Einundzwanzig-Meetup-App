package space.einundzwanzig.meetup.data.identity.passkey

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.fido.Fido
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import space.einundzwanzig.meetup.core.common.log.AppLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Credential Manager implementation of [PasskeyPrfService]. */
@Singleton
internal class AndroidPasskeyPrfService @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) : PasskeyPrfService {

    override suspend fun isSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            Fido.getFido2ApiClient(appContext).isUserVerifyingPlatformAuthenticatorAvailable.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.debug("Passkey", "Verfügbarkeitsprüfung fehlgeschlagen: ${e::class.simpleName}")
            false
        }
    }

    override suspend fun register(activity: Activity, userIdHex: String, userName: String): PasskeyPrfResult {
        val request = CreatePublicKeyCredentialRequest(
            PasskeyJson.registrationRequest(userIdHex, userName, PasskeyJson.randomChallenge()),
        )
        val response = try {
            CredentialManager.create(activity).createCredential(activity, request)
        } catch (e: CreateCredentialException) {
            throw mapCreate(e)
        }
        val json = (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw PasskeyPrfException(PasskeyPrfException.FAILED, "Unerwartete Antwort des Passkey-Dienstes.")
        val credentialId = PasskeyJson.credentialId(json)
            ?: throw PasskeyPrfException(PasskeyPrfException.FAILED, "Passkey ohne Kennung.")
        val secret = PasskeyJson.prfSecretHex(json)
            // Many authenticators deliver PRF only on get().
            ?: return PasskeyPrfResult(credentialId, unlock(activity, listOf(credentialId)).prfSecretHex)
        return PasskeyPrfResult(credentialId, secret)
    }

    override suspend fun unlock(activity: Activity, credentialIds: List<String>): PasskeyPrfResult {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetPublicKeyCredentialOption(PasskeyJson.authenticationRequest(credentialIds, PasskeyJson.randomChallenge())))
            .setPreferImmediatelyAvailableCredentials(false)
            .build()
        val response = try {
            CredentialManager.create(activity).getCredential(activity, request)
        } catch (e: GetCredentialException) {
            throw mapGet(e)
        }
        val json = (response.credential as? PublicKeyCredential)?.authenticationResponseJson
            ?: throw PasskeyPrfException(PasskeyPrfException.FAILED, "Unerwartete Antwort des Passkey-Dienstes.")
        val secret = PasskeyJson.prfSecretHex(json) ?: throw PasskeyPrfException(PasskeyPrfException.PRF_UNSUPPORTED)
        val id = PasskeyJson.credentialId(json) ?: credentialIds.firstOrNull().orEmpty()
        return PasskeyPrfResult(id, secret)
    }

    private fun mapCreate(e: CreateCredentialException): PasskeyPrfException =
        PasskeyErrors.create(e).also { logFailure("Registrierung", it.code, e) }

    private fun mapGet(e: GetCredentialException): PasskeyPrfException =
        PasskeyErrors.get(e).also { logFailure("Entsperren", it.code, e) }

    private fun logFailure(step: String, code: String, e: Exception) {
        AppLogger.warn("Passkey", "$step fehlgeschlagen: $code (${e::class.simpleName})")
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
