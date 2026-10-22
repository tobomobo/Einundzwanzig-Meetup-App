package space.einundzwanzig.meetup.data.migration.internal

import android.content.Context
import android.os.Build

/** The installed app's `versionCode` (seam for tests; spec 14 §4.7 cleanup release gate). */
internal fun interface AppVersionCode {
    fun current(): Long

    companion object {
        /** Reads `PackageInfo.longVersionCode` of this package. */
        fun of(context: Context): AppVersionCode = AppVersionCode {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }
    }
}
