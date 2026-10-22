import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// Opt-in, release-mode test distribution that can coexist with the installed app.
// Set ORG_GRADLE_PROJECT_performancePreview=true when building a prerelease.
val isPerformancePreview = providers.gradleProperty("performancePreview").orNull == "true"

android {
    namespace = "space.einundzwanzig.meetup"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    defaultConfig {
        applicationId = "space.einundzwanzig.meetup"
        if (isPerformancePreview) {
            applicationIdSuffix = ".preview"
        }
        resValue("string", "app_name", if (isPerformancePreview) "21Meetup Preview" else "21Meetup")
        minSdk = if (isPerformancePreview) flutter.minSdkVersion else 23
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        create("release") {
            val storeFilePath = keystoreProperties["storeFile"] as String?
            if (storeFilePath != null) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(storeFilePath)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (!isPerformancePreview && keystoreProperties["storeFile"] != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

flutter {
    source = "../.."
}
