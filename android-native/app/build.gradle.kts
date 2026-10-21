import java.util.Properties

plugins {
    id("einundzwanzig.android.application")
    id("einundzwanzig.android.compose")
    id("einundzwanzig.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "space.einundzwanzig.meetup"

    defaultConfig {
        // Same applicationId as the Flutter app: the native app ships as an upgrade and
        // migrates the Flutter data on first start (see :data:migration).
        applicationId = "space.einundzwanzig.meetup"
        // Flutter shipped versionCode 31 (1.7.2+31). Native builds start well above that.
        versionCode = 200
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        val releaseStore = props.getProperty("storeFile") ?: System.getenv("RELEASE_STORE_FILE")
        if (releaseStore != null) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = props.getProperty("storePassword") ?: System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = props.getProperty("keyAlias") ?: System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = props.getProperty("keyPassword") ?: System.getenv("RELEASE_KEY_PASSWORD")
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
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            signingConfig = signingConfigs.getByName("preview")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:l10n"))
    implementation(project(":core:navigation"))

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
}
