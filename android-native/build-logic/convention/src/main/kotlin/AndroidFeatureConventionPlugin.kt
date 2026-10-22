import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** A UI feature module: library + compose + hilt + the shared core UI/navigation/l10n modules. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("einundzwanzig.android.library")
            pluginManager.apply("einundzwanzig.android.compose")
            pluginManager.apply("einundzwanzig.android.hilt")
            dependencies {
                add("implementation", project(":core:common"))
                add("implementation", project(":core:ui"))
                add("implementation", project(":core:l10n"))
                add("implementation", project(":core:navigation"))
                add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
                add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
                add("implementation", libs.lib("androidx-hilt-lifecycle-viewmodel-compose"))
                add("implementation", libs.lib("androidx-navigation3-runtime"))
            }
        }
    }
}
