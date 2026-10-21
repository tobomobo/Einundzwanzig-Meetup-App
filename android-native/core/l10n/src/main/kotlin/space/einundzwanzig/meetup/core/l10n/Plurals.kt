package space.einundzwanzig.meetup.core.l10n

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * ARB plurals with an explicit `=0{...}` branch are generated as a `<plurals>` plus a
 * separate `<key>_zero` string, because Android never selects quantity "zero" for de/en/es.
 */
@Composable
fun pluralStringResourceWithZero(
    @PluralsRes id: Int,
    @StringRes zeroId: Int,
    count: Int,
    vararg formatArgs: Any,
): String = if (count == 0) {
    stringResource(zeroId, *formatArgs)
} else {
    pluralStringResource(id, count, *formatArgs)
}

fun android.content.res.Resources.getQuantityStringWithZero(
    @PluralsRes id: Int,
    @StringRes zeroId: Int,
    count: Int,
    vararg formatArgs: Any,
): String = if (count == 0) getString(zeroId, *formatArgs) else getQuantityString(id, count, *formatArgs)
