import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
            extensions.configure<LibraryExtension> {
                namespace = defaultNamespace()
                configureKotlinAndroid(this)
                // Optional per module: release builds of the app fail on a missing file.
                if (file("consumer-rules.pro").exists()) defaultConfig.consumerProguardFiles("consumer-rules.pro")
            }
            dependencies.add("implementation", libs.lib("kotlinx-serialization-json"))
        }
    }
}
