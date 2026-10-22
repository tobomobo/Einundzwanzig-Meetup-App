package space.einundzwanzig.meetup.feature.meetups

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.data.meetups.portal.PortalErrorKind
import space.einundzwanzig.meetup.data.meetups.portal.PortalLoginFailure
import space.einundzwanzig.meetup.feature.meetups.common.UiText
import space.einundzwanzig.meetup.feature.meetups.common.resolve
import java.util.Locale
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class PortalErrorLocalizationTest {
    private fun context(language: String): Context = ApplicationProvider.getApplicationContext<Context>().let { context ->
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
    }

    @Test
    fun `login diagnostic prefix is removed while signer causes remain intact`() {
        for (language in listOf("de", "en", "es")) {
            val context = context(language)
            val text = UiText.Prefixed(
                UiText.Res(L10nR.string.portalLoginFailed),
                UiText.Portal(PortalLoginFailure.PREFIX + "Permission denied", PortalErrorKind.LOGIN_FAILED),
            ).resolve(context)
            assertEquals(context.getString(L10nR.string.portalLoginFailed) + ": Permission denied", text)
        }
    }

    @Test
    fun `token storage cause is translated in all languages`() {
        for ((language, expected) in mapOf(
            "de" to "Token konnte nicht gespeichert werden",
            "en" to "Could not save the token",
            "es" to "No se pudo guardar el token",
        )) {
            val error = UiText.Portal(PortalLoginFailure.PREFIX + PortalLoginFailure.TOKEN_NOT_STORED, PortalErrorKind.LOGIN_FAILED)
            assertEquals(expected, error.resolve(context(language)))
        }
    }
}
