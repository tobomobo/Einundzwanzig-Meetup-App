import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import com.android.tools.r8.tracereferences.TraceReferences
import com.android.tools.r8.tracereferences.TraceReferencesCommand
import com.android.tools.r8.tracereferences.TraceReferencesKeepRules
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("einundzwanzig.android.application")
    id("einundzwanzig.android.compose")
    id("einundzwanzig.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

// Instrumentation APKs share the app's dependencies, but app R8 cannot otherwise see their calls.
// Trace the complete test bytecode (including the runner) before shrinking the app, so only APIs
// actually called across the APK boundary are pinned. AGP applies the app mapping to the test APK.
@CacheableTask
abstract class DeviceTestKeepRules : DefaultTask() {
    @get:Classpath abstract val appJars: ListProperty<RegularFile>
    @get:Classpath abstract val appDirectories: ListProperty<Directory>
    @get:Classpath abstract val testJars: ListProperty<RegularFile>
    @get:Classpath abstract val testDirectories: ListProperty<Directory>
    @get:Classpath abstract val bootClasspath: ConfigurableFileCollection
    @get:OutputFile abstract val rulesFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val appClasses = mutableSetOf<String>()
        fun archive(name: String, jars: List<RegularFile>, directories: List<Directory>, isApp: Boolean): File {
            val output = File(temporaryDir, name)
            val written = mutableSetOf<String>()
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                fun write(path: String, bytes: () -> ByteArray) {
                    if (!path.endsWith(".class") || (!isApp && path in appClasses) || !written.add(path)) return
                    if (isApp) appClasses.add(path)
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes())
                    zip.closeEntry()
                }
                jars.forEach { jar ->
                    ZipFile(jar.asFile).use { input ->
                        input.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                            write(entry.name) { input.getInputStream(entry).use { it.readBytes() } }
                        }
                    }
                }
                directories.forEach { directory ->
                    directory.asFile.walkTopDown().filter { it.isFile }.forEach { file ->
                        write(file.relativeTo(directory.asFile).invariantSeparatorsPath) { file.readBytes() }
                    }
                }
            }
            return output
        }
        val app = archive("app.jar", appJars.get(), appDirectories.get(), true)
        val tests = archive("tests.jar", testJars.get(), testDirectories.get(), false)
        val output = rulesFile.get().asFile
        output.parentFile.mkdirs()
        TraceReferences.run(
            TraceReferencesCommand.builder()
                .addLibraryFiles(bootClasspath.files.map { it.toPath() })
                .addTargetFiles(app.toPath())
                .addSourceFiles(tests.toPath())
                .setConsumer(TraceReferencesKeepRules.builder().setAllowObfuscation(true)
                    .setOutputPath(output.toPath()).build())
                .build()
        )
    }
}

val instrumentationBuildType = providers.gradleProperty("instrumentationBuildType").getOrElse("debug")
require(instrumentationBuildType in setOf("debug", "preview", "migrationTest")) {
    "instrumentationBuildType must be debug, preview or migrationTest"
}

android {
    namespace = "space.einundzwanzig.meetup"
    testBuildType = instrumentationBuildType

    defaultConfig {
        // Same applicationId as the Flutter app: the native app ships as an upgrade and
        // migrates the Flutter data on first start (see :data:migration).
        applicationId = "space.einundzwanzig.meetup"
        // Flutter shipped versionCode 31 (1.7.2+31) and split-ABI builds 1031/2031/4031 (spec 14 F4).
        // Scheme major * 10_000 + minor * 100 + patch (spec 14 §5.2.2): 2.0.0 = 20000.
        versionCode = 20000
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Same ABIs as the Flutter APK (spec 14 §5.2.6).
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
    }

    androidResources {
        localeFilters += listOf("de", "en", "es")
        generateLocaleConfig = true
    }

    signingConfigs {
        // Production signing: keystore.properties (local) or env vars (CI). Never committed.
        val props = Properties().apply {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val releaseStore = props.getProperty("storeFile") ?: System.getenv("E21_KEYSTORE_FILE")
        if (releaseStore != null) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = props.getProperty("storePassword") ?: System.getenv("E21_KEYSTORE_PASSWORD")
                keyAlias = props.getProperty("keyAlias") ?: System.getenv("E21_KEY_ALIAS")
                keyPassword = props.getProperty("keyPassword") ?: System.getenv("E21_KEY_PASSWORD")
                // Spec 14 §5.2.1: minSdk 26, v2 + v3 are sufficient.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
        // Spec 14 §5.2.5 migrationTest: the key of the Flutter APK installed on the test device, from
        // migrationtest.properties (local, never committed) or MIGRATION_TEST_* env vars. Without
        // one it uses the debug keystore, which is what locally built Flutter APKs are signed with.
        // Never put the release key here on developer machines.
        val migrationProps = Properties().apply {
            val f = rootProject.file("migrationtest.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val migrationStore = migrationProps.getProperty("storeFile") ?: System.getenv("MIGRATION_TEST_STORE_FILE")
        if (migrationStore != null) {
            create("migrationTest") {
                storeFile = rootProject.file(migrationStore)
                storePassword = migrationProps.getProperty("storePassword") ?: System.getenv("MIGRATION_TEST_STORE_PASSWORD")
                keyAlias = migrationProps.getProperty("keyAlias") ?: System.getenv("MIGRATION_TEST_KEY_ALIAS")
                keyPassword = migrationProps.getProperty("keyPassword") ?: System.getenv("MIGRATION_TEST_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
        // Preview builds: repository secrets if present, else the committed TEST-ONLY key.
        create("preview") {
            val envStore = System.getenv("PREVIEW_STORE_FILE")
            storeFile = if (envStore != null) file(envStore) else file("preview.keystore")
            storePassword = System.getenv("PREVIEW_STORE_PASSWORD") ?: "einundzwanzig-preview"
            keyAlias = System.getenv("PREVIEW_KEY_ALIAS") ?: "preview"
            keyPassword = System.getenv("PREVIEW_KEY_PASSWORD") ?: "einundzwanzig-preview"
        }
    }

    buildTypes {
        debug {
            // Installs next to the Flutter release build on the same device.
            applicationIdSuffix = ".native.debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Release code with its own applicationId: installs next to the Flutter app for testing.
        create("preview") {
            initWith(getByName("release"))
            isDebuggable = false
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            signingConfig = signingConfigs.getByName("preview")
            matchingFallbacks += listOf("release")
        }
        // Spec 14 §5.2.5 / F10: release source WITHOUT applicationId suffix, debuggable for
        // `run-as` upgrade fixtures. AGP disables optimization/obfuscation for debuggable apps;
        // explicitly leave this variant unminified. Preview instrumentation covers genuine R8.
        create("migrationTest") {
            initWith(getByName("release"))
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            versionNameSuffix = "-migrationtest"
            signingConfig = signingConfigs.findByName("migrationTest") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // Device assertions access production bindings through an entry point confined to test builds.
    sourceSets.getByName("migrationTest").java.srcDir("src/debug/java")
    if (instrumentationBuildType == "preview") {
        sourceSets.getByName("preview").java.srcDir("src/debug/java")
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Lint of :app also checks every module (checkDependencies). Findings that live in other
        // modules at the time of the integration are recorded here for their owners; new ones fail.
        baseline = file("lint-baseline.xml")
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

androidComponents {
    onVariants(selector().withBuildType("preview")) { variant ->
        variant.androidTest?.let { tests ->
            val keepRules = tasks.register<DeviceTestKeepRules>("generatePreviewDeviceTestKeepRules") {
                bootClasspath.from(sdkComponents.bootClasspath)
                rulesFile.set(layout.buildDirectory.file("generated/deviceTest/preview/referenced-api.pro"))
            }
            variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(keepRules).toGet(
                ScopedArtifact.CLASSES, DeviceTestKeepRules::appJars, DeviceTestKeepRules::appDirectories
            )
            tests.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(keepRules).toGet(
                ScopedArtifact.CLASSES, DeviceTestKeepRules::testJars, DeviceTestKeepRules::testDirectories
            )
            variant.proguardFiles.add(keepRules.flatMap { it.rulesFile })
            variant.proguardFiles.add(layout.projectDirectory.file("device-test-app.pro"))
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":core:l10n"))
    implementation(project(":core:navigation"))

    // Data modules the app wires directly (startup, widget, signer host, crest/avatar seams).
    implementation(project(":data:identity"))
    implementation(project(":data:migration"))
    implementation(project(":data:settings"))
    implementation(project(":data:badges"))
    implementation(project(":data:badgesync"))
    implementation(project(":data:calendar"))
    implementation(project(":data:mempool"))
    implementation(project(":data:news"))
    implementation(project(":data:profile"))
    implementation(project(":data:meetups"))
    implementation(project(":data:proofs"))
    implementation(project(":data:reputation"))
    implementation(project(":data:community"))

    // Every feature contributes its entries to the NavDisplay.
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:home"))
    implementation(project(":feature:wallet"))
    implementation(project(":feature:events"))
    implementation(project(":feature:badges"))
    implementation(project(":feature:reputation"))
    implementation(project(":feature:network"))
    implementation(project(":feature:admin"))
    implementation(project(":feature:proofs"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:meetups"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:news"))
    implementation(project(":feature:community"))
    implementation(project(":feature:bitcoin"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:backup"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.biometric) // FragmentActivity host for BiometricPrompt (spec 14 F8)
    implementation(libs.androidx.browser) // Custom Tabs for Navigator.openUrl
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Glance size-mode tests of the widget (spec 14 §3.2).
    testImplementation(libs.androidx.glance.appwidget.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(project(":core:crypto"))
    androidTestImplementation(project(":core:nostr"))
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.mlkit.barcode.scanning)
    androidTestImplementation(libs.zxing.core)
    androidTestImplementation(libs.osmdroid)
}
