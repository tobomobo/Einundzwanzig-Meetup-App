package space.einundzwanzig.meetup.testing

import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.MainActivity

/** Includes the API 26–32 attachBaseContext path in the device matrix, not just library code. */
@RunWith(AndroidJUnit4::class)
class ActivityDeviceTest {
    @Test fun realActivityCreatesAndRecreatesWithItsProductionGraph() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.isAttachedToWindow)
                assertTrue(activity.findViewById<ViewGroup>(android.R.id.content).childCount > 0)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(MainActivity::class.java, activity.javaClass)
                assertTrue(activity.window.decorView.isAttachedToWindow)
            }
        }
    }
}
