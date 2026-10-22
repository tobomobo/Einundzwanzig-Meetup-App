package space.einundzwanzig.meetup.core.ui.format

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Locale-independent formatting shared by many screens. The Flutter app hard-codes these
 * formats (German style in every language); `NumberFormat`/`DateTimeFormatter` with a locale
 * would produce different output (spec 12 §14.14, spec 13 §7.2).
 */
object Formats {
    /**
     * Groups digits with `.` every three places from the right, also for negatives
     * (`CurrencyService.groupInt`): `95000 → "95.000"`, `-1234 → "-1.234"`.
     */
    fun groupInt(value: Long): String {
        val negative = value < 0
        val digits = if (value == Long.MIN_VALUE) "9223372036854775808" else abs(value).toString()
        val out = StringBuilder(digits.length + digits.length / 3 + 1)
        if (negative) out.append('-')
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) out.append('.')
            out.append(c)
        }
        return out.toString()
    }

    /** [groupInt] for Int values. */
    fun groupInt(value: Int): String = groupInt(value.toLong())

    /** `"1.779 sats"` style amount (grouped, unit appended with a space). */
    fun sats(amount: Long): String = "${groupInt(amount)} sats"

    /** Block height for wallet cards: grouped, `"---"` for `<= 0` (`formatBlockWallet`). */
    fun blockHeight(height: Long): String = if (height <= 0) "---" else groupInt(height)

    /** `d.M.yyyy`, unpadded (`15.3.2025`) — wallet cards, news, calendar lines. */
    fun dateShort(date: LocalDate): String = "${date.dayOfMonth}.${date.monthValue}.${date.year}"

    /** `dd.MM.yyyy`, zero padded (`15.03.2025`) — badge details, share card. */
    fun datePadded(date: LocalDate): String =
        "${date.dayOfMonth.pad2()}.${date.monthValue.pad2()}.${date.year}"

    /** `HH:mm`, 24 h, zero padded. */
    fun time(dateTime: LocalDateTime): String = "${dateTime.hour.pad2()}:${dateTime.minute.pad2()}"

    /** `dd.MM.yyyy  HH:mm` with **two** spaces (scan time on details and share card). */
    fun dateTimePadded(dateTime: LocalDateTime): String = "${datePadded(dateTime.toLocalDate())}  ${time(dateTime)}"

    private fun Int.pad2(): String = toString().padStart(2, '0')
}
