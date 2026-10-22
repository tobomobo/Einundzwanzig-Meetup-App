import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

object ProjectConfig {
    const val COMPILE_SDK = 37
    const val MIN_SDK = 26
    const val TARGET_SDK = 36
    const val NAMESPACE_ROOT = "space.einundzwanzig.meetup"
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

/** Derives a namespace like space.einundzwanzig.meetup.core.crypto from the project path :core:crypto. */
internal fun Project.defaultNamespace(): String =
    ProjectConfig.NAMESPACE_ROOT + path.replace(':', '.').replace('-', '_')

internal fun Project.configureKotlinAndroid(common: CommonExtension) {
    common.apply {
        compileSdk = ProjectConfig.COMPILE_SDK
        defaultConfig.minSdk = ProjectConfig.MIN_SDK
        compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        compileOptions.targetCompatibility = JavaVersion.VERSION_17
        compileOptions.isCoreLibraryDesugaringEnabled = true
        testOptions.unitTests.isIncludeAndroidResources = true
        testOptions.unitTests.isReturnDefaultValues = true
        lint.abortOnError = true
        lint.checkDependencies = true
        lint.warningsAsErrors = false
    }
    extensions.configure(KotlinAndroidProjectExtension::class.java) {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
    }
    dependencies {
        add("coreLibraryDesugaring", libs.lib("desugar-jdk-libs"))
        add("implementation", libs.lib("kotlinx-coroutines-android"))
        add("testImplementation", libs.lib("junit"))
        add("testImplementation", libs.lib("kotlinx-coroutines-test"))
        add("testImplementation", libs.lib("robolectric"))
        add("testImplementation", libs.lib("turbine"))
        add("testImplementation", libs.lib("androidx-test-core"))
        add("testImplementation", libs.lib("androidx-test-ext-junit"))
    }
}
