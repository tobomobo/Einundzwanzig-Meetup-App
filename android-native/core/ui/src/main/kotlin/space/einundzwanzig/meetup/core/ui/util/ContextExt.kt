package space.einundzwanzig.meetup.core.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** Unwraps [ContextWrapper]s until the hosting [Activity] is found, or null. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}
