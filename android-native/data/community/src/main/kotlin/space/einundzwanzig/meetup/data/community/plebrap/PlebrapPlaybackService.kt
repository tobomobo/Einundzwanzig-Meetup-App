package space.einundzwanzig.meetup.data.community.plebrap

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import space.einundzwanzig.meetup.data.community.R
import space.einundzwanzig.meetup.data.community.plebrap.internal.DefaultPlebrapPlayer

/**
 * Background playback of the PlebRap player with a media notification and system media controls
 * (lock screen, Bluetooth, Wear/Auto). Foreground service type `mediaPlayback`; declared in this
 * module's manifest.
 *
 * The service only hosts the [MediaSession] around the process-wide player owned by
 * [PlebrapPlayer]; it never releases that player, so state survives the service being stopped.
 * [PlebrapPlayer] binds it (via a `MediaController`) as soon as playback is requested; Media3
 * promotes it to the foreground while playing and lets it stop when paused and unbound, or when
 * the task is removed while not playing.
 *
 * The notification's content intent opens the app's launcher activity. Only the app and system
 * media controllers may connect (see [ConnectionGate]).
 */
@OptIn(UnstableApi::class)
class PlebrapPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // Resolving the PlebrapPlayer (not just the ExoPlayer) guarantees the player logic is wired
        // even when a system controller (Bluetooth, media resumption) starts the service first.
        val player = EntryPointAccessors
            .fromApplication(applicationContext, PlebrapServiceEntryPoint::class.java)
            .plebrapPlayer()
            .sessionPlayer
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.data_community_ic_plebrap_notification)
            },
        )
        val builder = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setCallback(ConnectionGate())
        launchIntent()?.let(builder::setSessionActivity)
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // Release only the session; the player belongs to the process-wide PlebrapPlayer.
        session?.release()
        session = null
        super.onDestroy()
    }

    /**
     * The service is exported (system controllers must bind), so connections are filtered: the
     * app itself, the media notification controller and trusted controllers (holders of
     * `MEDIA_CONTENT_CONTROL` or an enabled notification listener: System UI, Bluetooth,
     * Auto/Wear) are accepted; any other installed app is rejected. The platform's anonymous
     * legacy controller ([MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME], used for
     * media keys and pre-API-28 platform controllers whose caller Android does not reveal) is
     * accepted too, otherwise headset buttons would stop working on API 26/27.
     */
    private inner class ConnectionGate : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val allowed = controller.packageName == packageName ||
                session.isMediaNotificationController(controller) ||
                session.isAutomotiveController(controller) ||
                session.isAutoCompanionController(controller) ||
                controller.isTrusted ||
                controller.packageName == MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME
            return if (allowed) super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()
        }
    }

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val SESSION_ID = "plebrap"
    }
}

/** Hilt access for the (non-injected) [PlebrapPlaybackService]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PlebrapServiceEntryPoint {
    fun plebrapPlayer(): DefaultPlebrapPlayer
}
