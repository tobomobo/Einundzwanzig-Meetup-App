package space.einundzwanzig.meetup.data.identity

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class IdentityErrorMessageTest {
    private fun text(error: Throwable, language: String): String {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return app.createConfigurationContext(config).getString(error.identityErrorMessage)
    }

    @Test fun memoryFailureIsLocalizedInAllSupportedLanguages() {
        val failure = KeyOperationException(IdentityError.MEMORY, OutOfMemoryError())
        assertTrue(text(failure, "de").contains("Arbeitsspeicher"))
        assertTrue(text(failure, "en").contains("memory"))
        assertTrue(text(failure, "es").contains("memoria"))
    }

    @Test fun signerAndInputFailuresNeverEchoExceptionOrRemoteSecretText() {
        val secret = "sensitive-private-material"
        val failures = listOf(
            KeyFormatException(secret),
            SigningException(secret, error = SigningError.BunkerRejected(secret)),
            SigningException(secret, error = SigningError.SignerUnreadable(space.einundzwanzig.meetup.data.identity.signer.SignerActor.AMBER, secret)),
        )
        for (error in failures) for (locale in listOf("de", "en", "es")) {
            val message = text(error, locale)
            assertTrue(message.isNotBlank())
            assertFalse(message.contains(secret))
        }
    }
}
