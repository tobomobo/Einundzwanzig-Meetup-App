package space.einundzwanzig.meetup.data.community.v4v

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.describeError

/**
 * Hands a bolt11 invoice to an installed Lightning wallet, port of `MainActivity.payLightningInvoice`
 * (spec 11 §2.10, §11). Not UI: the caller shows the copy-fallback sheet when this returns `false`.
 *
 * Package visibility: this module's manifest declares the `lightning:` `<queries>` entry needed by
 * the `resolveActivity` check.
 */
object LightningInvoiceLauncher {

    /** Chooser title of the Flutter app (hard-coded German there; callers may pass a localized one). */
    const val DEFAULT_CHOOSER_TITLE: String = "Lightning-Wallet wählen"

    /** The `lightning:<bolt11>` VIEW intent. */
    fun invoiceIntent(bolt11: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("lightning:$bolt11"))

    /**
     * V4V behaviour: `false` if no app handles `lightning:`; otherwise starts
     * `Intent.createChooser(intent, chooserTitle)` and returns `true`. Any exception → `false`.
     * A non-Activity [context] gets `FLAG_ACTIVITY_NEW_TASK`.
     */
    fun openWithChooser(
        context: Context,
        bolt11: String,
        chooserTitle: CharSequence = DEFAULT_CHOOSER_TITLE,
    ): Boolean = try {
        val intent = invoiceIntent(bolt11)
        if (intent.resolveActivity(context.packageManager) == null) {
            false
        } else {
            val chooser = Intent.createChooser(intent, chooserTitle)
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        }
    } catch (e: Exception) {
        AppLogger.debug(TAG, "Wallet-Auswahl fehlgeschlagen: ${describeError(e)}")
        false
    }

    /**
     * News-zap behaviour: plain `startActivity` without chooser; `false` on
     * [ActivityNotFoundException] (or any other failure).
     */
    fun open(context: Context, bolt11: String): Boolean = try {
        val intent = invoiceIntent(bolt11)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: Exception) {
        AppLogger.debug(TAG, "Wallet-Start fehlgeschlagen: ${describeError(e)}")
        false
    }

    private const val TAG = "V4VService"
}
