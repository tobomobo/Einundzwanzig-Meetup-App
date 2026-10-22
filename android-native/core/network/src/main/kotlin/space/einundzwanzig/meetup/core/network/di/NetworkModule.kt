package space.einundzwanzig.meetup.core.network.di

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.network.AppJson
import space.einundzwanzig.meetup.core.network.UserAgent
import space.einundzwanzig.meetup.core.network.buildAppOkHttpClient
import space.einundzwanzig.meetup.core.network.buildWebSocketClient
import javax.inject.Qualifier
import javax.inject.Singleton

/** The app's default User-Agent string, `Einundzwanzig-Meetup-App/<versionName> (Android)`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppUserAgent

/**
 * The [OkHttpClient] for WebSockets (`client.newWebSocket`, used by `:core:nostr` relay sockets).
 *
 * It shares connection pool, timeouts and interceptors with the default client but has its own
 * unbounded dispatcher: OkHttp keeps a dispatcher slot busy for the whole lifetime of an open
 * WebSocket, so sockets on the default client would block HTTP calls. Never open WebSockets on
 * the unqualified client.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WebSocketClient

/**
 * Provides the shared [OkHttpClient] for HTTP, the [WebSocketClient] for relay sockets, the
 * default [AppUserAgent] and [AppJson] as [Json].
 *
 * Derive per-feature HTTP variants with `client.newBuilder()` (shares the connection pool and
 * dispatcher) instead of building new clients.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {

    @Provides
    @Singleton
    @AppUserAgent
    fun userAgent(@ApplicationContext context: Context): String = UserAgent.forVersion(versionName(context))

    @Provides
    @Singleton
    fun okHttpClient(@AppUserAgent userAgent: String): OkHttpClient = buildAppOkHttpClient(userAgent)

    @Provides
    @Singleton
    @WebSocketClient
    fun webSocketClient(base: OkHttpClient): OkHttpClient = buildWebSocketClient(base)

    @Provides
    fun json(): Json = AppJson

    private fun versionName(context: Context): String? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
