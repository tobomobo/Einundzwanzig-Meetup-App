package space.einundzwanzig.meetup.feature.community

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import space.einundzwanzig.meetup.data.community.plebrap.PlebrapPlayer
import space.einundzwanzig.meetup.data.community.v4v.V4vResult
import space.einundzwanzig.meetup.data.community.v4v.V4vService
import space.einundzwanzig.meetup.data.proofs.satoshiduell.DuellStatus
import space.einundzwanzig.meetup.data.proofs.satoshiduell.SatoshiDuellClient
import space.einundzwanzig.meetup.feature.community.di.PortalCoursesSource

class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

class FakeDuellClient(var status: DuellStatus = DuellStatus.EMPTY, var fail: Boolean = false) : SatoshiDuellClient {
    var calls = 0
    override suspend fun fetchStatus(forceRefresh: Boolean): DuellStatus {
        calls++
        if (fail) error("boom")
        return status
    }
    override suspend fun openDuelCount(forceRefresh: Boolean): Int = fetchStatus(forceRefresh).actionCount
}

class FakePortalCourses(
    var courses: List<JsonObject> = emptyList(),
    var lecturers: List<JsonObject> = emptyList(),
    var courseDetails: Map<Int, JsonObject> = emptyMap(),
    var lecturerDetails: Map<Int, JsonObject> = emptyMap(),
    var failLists: Boolean = false,
    var failDetails: Boolean = false,
) : PortalCoursesSource {
    val log = mutableListOf<String>()
    override suspend fun getCourses(): List<JsonObject> {
        log += "courses"
        if (failLists) error("offline")
        return courses
    }
    override suspend fun getCourse(id: Int): JsonObject? {
        log += "course:$id"
        if (failDetails) error("offline")
        return courseDetails[id]
    }
    override suspend fun getLecturers(): List<JsonObject> {
        log += "lecturers"
        if (failLists) error("offline")
        return lecturers
    }
    override suspend fun getLecturer(id: Int): JsonObject? {
        log += "lecturer:$id"
        if (failDetails) error("offline")
        return lecturerDetails[id]
    }
}

class FakeV4vService(var result: V4vResult = V4vResult.Success("lnbc1")) : V4vService {
    val calls = mutableListOf<Triple<Long, String, String?>>()
    override suspend fun createInvoice(amountSats: Long, address: String, comment: String?): V4vResult {
        calls += Triple(amountSats, address, comment)
        return result
    }
}

class FakePlebrapPlayer : PlebrapPlayer {
    override val index = MutableStateFlow<Int?>(null)
    override val loading = MutableStateFlow(false)
    override val loadErrors = MutableStateFlow(0)
    override val cacheProgress = MutableStateFlow(0f)
    override val isPlaying = MutableStateFlow(false)
    override val durationMs = MutableStateFlow<Long?>(null)
    override val seekable = MutableStateFlow(false)
    val position = MutableStateFlow(0L)
    override val positionMs: Flow<Long> = position
    val commands = mutableListOf<String>()
    override suspend fun playIndex(i: Int) { commands += "play:$i" }
    override suspend fun toggle() { commands += "toggle" }
    override suspend fun next() { commands += "next" }
    override suspend fun prev() { commands += "prev" }
    override suspend fun seekTo(positionMs: Long) { commands += "seek:$positionMs" }
}

fun portalObject(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) ->
        when (v) {
            null -> Unit
            is Int -> put(k, v)
            is String -> put(k, v)
            is JsonObject -> put(k, v)
            is kotlinx.serialization.json.JsonElement -> put(k, v)
            else -> put(k, JsonPrimitive(v.toString()))
        }
    }
}
