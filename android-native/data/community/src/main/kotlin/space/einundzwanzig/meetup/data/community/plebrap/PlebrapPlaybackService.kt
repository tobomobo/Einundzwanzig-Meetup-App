package space.einundzwanzig.meetup.data.community.plebrap

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
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
 * promotes it to the foreground while playing. When the task is removed while not playing, the
 * session is released as well (see [onTaskRemoved]), which disconnects the app's controller so the
 * service can really be destroyed.
 *
 * Artwork for the notification / system UI is loaded through the shared [OkHttpClient]
 * (User-Agent, cleartext guard, connection pool), like the audio itself.
 *
 * The notification's content intent opens the app's launcher activity. Only the app and system
 * media controllers may connect (see [ConnectionGate]).
 */
@OptIn(UnstableApi::class)
class PlebrapPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.data_community_ic_plebrap_notification)
            },
        )
        session = buildSession()
    }

    private fun buildSession(): MediaSession {
        // Resolving the PlebrapPlayer (not just the ExoPlayer) guarantees the player logic is wired
        // even when a system controller (Bluetooth, media resumption) starts the service first.
        val entryPoint = EntryPointAccessors
            .fromApplication(applicationContext, PlebrapServiceEntryPoint::class.java)
        val player = entryPoint.plebrapPlayer().sessionPlayer
        val builder = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setCallback(ConnectionGate())
            // Media3 wraps this in its size-limiting / caching loaders; only the transport changes.
            .setBitmapLoader(
                DataSourceBitmapLoader.Builder(this)
                    .setDataSourceFactory(
                        DefaultDataSource.Factory(this, OkHttpDataSource.Factory(entryPoint.okHttpClient())),
                    )
                    .build(),
            )
        launchIntent()?.let(builder::setSessionActivity)
        return builder.build()
    }

    /** Re-creates the session if [onTaskRemoved] released it but the service is still alive. */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        session ?: buildSession().also { session = it }

    /**
     * Media3's default stops the service when the task is removed while not playing, but the app's
     * bound `MediaController` keeps a stopped service alive (with its paused notification and
     * session) for the rest of the process. Releasing the session here disconnects that controller
     * (it unbinds; the player re-binds on the next command), so the service is destroyed.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val stopping = !isPlaybackOngoing || session?.player?.isPlaying != true
        super.onTaskRemoved(rootIntent)
        if (stopping) {
            session?.let {
                if (isSessionAdded(it)) removeSession(it)
                it.release()
            }
            session = null
        }
    }

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
    fun okHttpClient(): OkHttpClient
}
