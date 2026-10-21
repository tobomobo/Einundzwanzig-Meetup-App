package space.einundzwanzig.meetup.data.mempool

/**
 * Classification of a failed [MempoolService.testHost] for the settings screen
 * (spec 13 §2.3 `test()`), so the UI only maps it to strings:
 *
 * - [Blocked] → `"${mempoolTestBlocked} (HTTP $statusCode)"`
 * - [Http] → `"${mempoolTestFail} — HTTP $statusCode"`
 * - [OnionUnreachable] → `mempoolTestOnionFail`
 * - [Other] → `"${mempoolTestFail} — $typeName"`
 */
sealed interface HostTestFailure {
    /** Non-200 answer that looks like WAF/rate-limit blocking. */
    data class Blocked(val statusCode: Int) : HostTestFailure

    /** Any other HTTP failure (including a 200 body that is not a height). */
    data class Http(val statusCode: Int) : HostTestFailure

    /** Network failure on an onion host (Orbot probably not running). */
    data object OnionUnreachable : HostTestFailure

    /** Network failure; [typeName] is the exception's simple class name. */
    data class Other(val typeName: String) : HostTestFailure

    /** Factory. */
    companion object {
        /**
         * Classifies [error] thrown by `testHost(rawHost)`.
         *
         * Callers must not treat cancellation as a failed test: rethrow
         * [kotlinx.coroutines.CancellationException] before classifying. As a safety net for a
         * broad `catch (e: Exception)`, a [kotlinx.coroutines.CancellationException] passed here
         * is rethrown instead of being classified.
         */
        fun of(error: Throwable, rawHost: String): HostTestFailure = when {
            error is kotlinx.coroutines.CancellationException -> throw error
            error is MempoolHttpException && error.looksBlocked -> Blocked(error.statusCode)
            error is MempoolHttpException -> Http(error.statusCode)
            MempoolHosts.normalize(rawHost).contains(".onion") -> OnionUnreachable
            else -> Other(errorTypeName(error))
        }

        /** Simple class name of [error], the native counterpart of Dart's `runtimeType`. */
        fun errorTypeName(error: Throwable): String = error::class.simpleName ?: error::class.java.name
    }
}
