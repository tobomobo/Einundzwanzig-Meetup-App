package space.einundzwanzig.meetup.feature.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import space.einundzwanzig.meetup.data.chat.ChatRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The local date and zone the message time labels are formatted against (spec 11 §2.1:
 * `HH:mm` today, else `d.M. HH:mm`). Only the date matters, so the state changes once a day.
 */
@Immutable
data class ChatClock(val today: LocalDate, val zone: ZoneId) {
    /** Time label of a message sent at [createdAt]. */
    fun label(createdAt: Instant): String =
        ChatRules.timeLabel(createdAt, today.atStartOfDay(zone).toInstant(), zone)

    companion object {
        /** The system clock and zone right now. */
        fun system(): ChatClock {
            val zone = ZoneId.systemDefault()
            return ChatClock(LocalDate.now(zone), zone)
        }
    }
}

/**
 * [ChatClock] that follows the system: re-read on every minute tick and on date, time and time
 * zone changes while the screen is composed, so labels switch to `d.M. HH:mm` after midnight.
 * Equal values do not recompose, so the minute ticks cost nothing until the date changes.
 */
@Composable
fun rememberChatClock(): ChatClock {
    val context = LocalContext.current
    var clock by remember { mutableStateOf(ChatClock.system()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                clock = ChatClock.system()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Catch a date change that happened while the receiver was not registered.
        clock = ChatClock.system()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return clock
}
