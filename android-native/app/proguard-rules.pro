# R8 rules of the app (spec 14 §5.2.4). Most libraries ship consumer rules: kotlinx.serialization,
# Hilt/Dagger, OkHttp, Glance, WorkManager, Media3, CameraX, ML Kit, Credential Manager, Coil,
# Navigation 3, osmdroid. Do not add blanket -keep rules.

# Readable stack traces in the diagnostic log (deobfuscate with mapping.txt from the release artifacts).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# secp256k1-kmp-jni-android: the native loader looks the JNI classes up by reflection.
-keep class fr.acinq.secp256k1.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# BouncyCastle (lightweight API only, no JCA provider registration) references optional JNDI/LDAP classes.
-dontwarn javax.naming.**

# Tink (ESP recovery in :data:migration) ships its own rules; annotation-only references:
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Navigation 3 saves the back stack by class name and restores it through the generated
# kotlinx.serialization serializers of the @Serializable NavKeys (looked up reflectively via the
# companion's serializer()). Keep the destinations' names and their serializers.
-keepnames class space.einundzwanzig.meetup.core.navigation.** implements androidx.navigation3.runtime.NavKey
-keep,includedescriptorclasses class space.einundzwanzig.meetup.core.navigation.**$$serializer { *; }
-keepclassmembers class space.einundzwanzig.meetup.core.navigation.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class space.einundzwanzig.meetup.core.navigation.** {
    public static ** INSTANCE;
}

# Glance widget receiver / router are referenced from the manifest (kept by AAPT rules); the
# action callback is instantiated by class name.
-keep class space.einundzwanzig.meetup.widget.RefreshWidgetAction { <init>(); }
# ML Kit discovers component registrars from manifest metadata and invokes their no-arg
# constructors reflectively. Keeping their names alone leaves the QR scanner without components.
-keepclassmembers class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
