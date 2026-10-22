package space.einundzwanzig.meetup.feature.badges

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.badges.security.ExpiryInfo
import space.einundzwanzig.meetup.data.identity.review.AppReviewDemo
import space.einundzwanzig.meetup.data.issuance.session.SessionRemaining
import space.einundzwanzig.meetup.feature.badges.camera.firstAcceptedBadgeQr
import space.einundzwanzig.meetup.feature.badges.common.BadgeTexts
import space.einundzwanzig.meetup.feature.badges.scanner.ScanResultUi
import space.einundzwanzig.meetup.feature.badges.scanner.ScannerSnackbar
import space.einundzwanzig.meetup.feature.badges.scanner.confirmationText
import space.einundzwanzig.meetup.feature.badges.scanner.snackbarSpec
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class BadgeTextsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val res get() = context.resources

    @Test
    fun `german expiry texts match Flutter`() {
        for (info in listOf(ExpiryInfo.Valid(3 * 3600 + 12 * 60), ExpiryInfo.Valid(59 * 60), ExpiryInfo.Expired(3780), ExpiryInfo.Expired(30))) {
            assertEquals(info.toGermanString(), BadgeTexts.expiry(res, info))
        }
        assertEquals(res.getString(L10nR.string.verifyNoExpiry), BadgeTexts.expiry(res, ExpiryInfo.NoExpiry))
    }

    @Test
    fun `german remaining times match Flutter`() {
        for (r in listOf(SessionRemaining.Expired, SessionRemaining.HoursMinutes(3, 7), SessionRemaining.Minutes(45))) {
            assertEquals(r.toGermanString(), BadgeTexts.remaining(res, r))
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun `english expiry is localized`() {
        assertEquals("Valid for 3h 12min", BadgeTexts.expiry(res, ExpiryInfo.Valid(3 * 3600 + 12 * 60)))
        assertEquals("Expired 5min ago", BadgeTexts.expiry(res, ExpiryInfo.Expired(300)))
    }

    @Test
    fun `found message follows the Flutter concatenation`() {
        val info = foundInfo()
        val expected = res.getString(L10nR.string.verifyBadgeFound) + "\n\n" +
            res.getString(L10nR.string.verifyMsgLocation, "Würzburg, DE") + "\n" +
            res.getString(L10nR.string.verifyMsgBlock, "915000") + "\n" +
            res.getString(L10nR.string.verifyMsgSignedBy, "npub1abc...xyz") + "\n" +
            res.getString(L10nR.string.verifyMsgProof) + "\n" +
            res.getString(L10nR.string.mvBadgeBound) + "\n\n" +
            res.getString(L10nR.string.mvKnownOrganizer, "Satoshi") + "\n\n" +
            res.getString(L10nR.string.verifyMsgTagExpiry, "Gültig noch 3h 12min")
        assertEquals(expected, confirmationText(res, ScanResultUi.Found(found().pending, info)))
    }

    @Test
    fun `unknown signer message ends with the warning`() {
        val text = confirmationText(res, ScanResultUi.Found(found().pending, foundInfo(unknownSigner = true)))
        assert(text.contains(res.getString(L10nR.string.mvUnknownSigner)))
    }

    @Test
    fun `snackbars use Flutter's colours and durations`() {
        assertEquals(Tone.Success, snackbarSpec(res, ScannerSnackbar.SAVED).tone)
        assertEquals(5_000L, snackbarSpec(res, ScannerSnackbar.UNVERIFIED_PRESENCE).durationMillis)
        assertEquals(8_000L, snackbarSpec(res, ScannerSnackbar.HELD_ASIDE).durationMillis)
        assertEquals(Tone.Error, snackbarSpec(res, ScannerSnackbar.SAVE_FAILED).tone)
        assertEquals(Tone.Warning, snackbarSpec(res, ScannerSnackbar.DUPLICATE).tone)
        assertEquals(3_000L, snackbarSpec(res, ScannerSnackbar.CO_ATTENDANCE_PUBLISHED).durationMillis)
        assertEquals(6_000L, snackbarSpec(res, ScannerSnackbar.CO_ATTENDANCE_FAILED).durationMillis)
        assertEquals(res.getString(L10nR.string.caPublishFailed), snackbarSpec(res, ScannerSnackbar.CO_ATTENDANCE_FAILED).message)
    }

    @Test
    fun `badge camera accepts demo and badge JSON only`() {
        assertEquals(AppReviewDemo.PAYLOAD, firstAcceptedBadgeQr(listOf("hello", AppReviewDemo.PAYLOAD)))
        assertEquals("{\"t\":\"B\"}", firstAcceptedBadgeQr(listOf("[1]", "{\"x\":1}", "{\"t\":\"B\"}")))
        assertEquals("{\"type\":\"badge\"}", firstAcceptedBadgeQr(listOf("{\"type\":\"badge\"}")))
        assertNull(firstAcceptedBadgeQr(listOf("21v3:abc", null, "{}")))
    }
}
