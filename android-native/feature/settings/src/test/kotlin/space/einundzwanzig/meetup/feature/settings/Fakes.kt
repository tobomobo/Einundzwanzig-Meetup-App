package space.einundzwanzig.meetup.feature.settings

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.LogEntry
import space.einundzwanzig.meetup.core.ui.theme.ThemeMode
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyRepository
import space.einundzwanzig.meetup.data.settings.SettingsRepository
import space.einundzwanzig.meetup.data.settings.locale.LocaleController

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

class FakeSettingsRepository(haptic: Boolean = true, theme: ThemeMode = ThemeMode.DARK) : SettingsRepository {
    val locale = MutableStateFlow<String?>(null)
    override val hapticEnabled = MutableStateFlow(haptic)
    override val themeMode = MutableStateFlow(theme)
    var writesSucceed = true
    override val appLocale: Flow<String?> = locale
    override suspend fun getAppLocale(): String? = locale.value
    override suspend fun setAppLocale(code: String?): Boolean {
        locale.value = code
        return writesSucceed
    }
    override suspend fun isHapticEnabled(): Boolean = hapticEnabled.value
    override suspend fun setHapticEnabled(enabled: Boolean): Boolean {
        if (writesSucceed) hapticEnabled.value = enabled
        return writesSucceed
    }
    override suspend fun getThemeMode(): ThemeMode = themeMode.value
    override suspend fun setThemeMode(mode: ThemeMode): Boolean {
        if (writesSucceed) themeMode.value = mode
        return writesSucceed
    }
    override suspend fun getNostrLastSeen(): Long = 0
    override suspend fun setNostrLastSeen(epochSeconds: Long): Boolean = true
}

class FakeLocaleController(initial: String? = null) : LocaleController {
    val stored = MutableStateFlow(initial)
    val applied = mutableListOf<String?>()
    override val appLocale: Flow<String?> = stored
    override val isManagedByPlatform: Boolean = true
    override suspend fun current(): String? = stored.value
    override suspend fun setLocale(code: String?): Boolean {
        applied += code
        stored.value = code
        return true
    }
    override suspend fun syncWithSystem() = Unit
    override fun currentBlocking(): String? = stored.value
    override fun wrapContext(base: Context): Context = base
}

class FakeCurrencyRepository(initial: String = "EUR") : CurrencyRepository {
    override val current = MutableStateFlow(initial)
    val sets = mutableListOf<String>()
    override suspend fun load() = Unit
    override suspend fun read(): String = current.value
    override suspend fun set(code: String) {
        sets += code
        if (code in listOf("EUR", "USD", "GBP", "CHF", "CAD", "AUD", "JPY")) current.value = code
    }
    override suspend fun reset() {
        current.value = "EUR"
    }
}

class FakeDiagnosticLog(initial: List<LogEntry> = emptyList()) : DiagnosticLog {
    override val entries = MutableStateFlow(initial)
    override val verbose = MutableStateFlow(false)
    var clears = 0
    private fun add(level: String, tag: String, message: String) {
        entries.value = entries.value + LogEntry(1_760_000_000_000L + entries.value.size, level, tag, message)
    }
    override fun debug(tag: String, message: String) {
        if (verbose.value) add("DEBUG", tag, message)
    }
    override fun info(tag: String, message: String) = add("INFO", tag, message)
    override fun diag(tag: String, message: String) = add("INFO", tag, message)
    override fun warn(tag: String, message: String, error: Any?) = add("WARN", tag, message)
    override fun error(tag: String, message: String, error: Any?, stack: Array<StackTraceElement>?) = add("ERROR", tag, message)
    override fun security(tag: String, message: String) = add("SEC", tag, message)
    override fun section(title: String) = add("INFO", "ooo", title)
    override fun setVerbose(on: Boolean) {
        verbose.value = on
    }
    override suspend fun clear() {
        clears++
        entries.value = emptyList()
    }
}

/** NIP-44 probe whose answer the test controls. */
class FakeNip44Probe(var result: Boolean = true) : Nip44Probe {
    var calls = 0
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun canNip44(): Boolean {
        calls++
        gate?.await()
        return result
    }
}

fun entry(level: String, tag: String = "T", message: String = "m", time: Long = 1_760_000_000_000L) =
    LogEntry(time, level, tag, message)
