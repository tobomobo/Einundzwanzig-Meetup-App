package space.einundzwanzig.meetup.data.migration.internal

import android.content.Context
import space.einundzwanzig.meetup.data.migration.legacy.FlutterPrefs
import space.einundzwanzig.meetup.data.migration.legacy.Fss
import space.einundzwanzig.meetup.data.migration.legacy.SharedPrefsFiles
import java.io.File

/** File-level operations on the Flutter leftovers (spec 14 §4.1, §4.7, §4.9). */
internal class LegacyFiles(
    private val context: Context,
    val prefsFiles: SharedPrefsFiles = SharedPrefsFiles(context),
    private val migrationDir: File = File(context.noBackupFilesDir, MigrationStateStore.DIR_NAME),
) {
    val snapshotDir: File get() = File(migrationDir, MigrationStateStore.SNAPSHOT_DIR)

    /** Step 2: any of the three Flutter storage files (or its `.bak`) exists. */
    fun anyFlutterDataPresent(): Boolean =
        prefsFiles.exists(FlutterPrefs.FILE) || prefsFiles.exists(Fss.DATA) || prefsFiles.exists(Fss.KEYS)

    /** Names of the flutter_secure_storage files: `FlutterSecureStorage*` and `FlutterSecureKeyStorage`. */
    private fun secureStorageFileNames(): Set<String> =
        prefsFiles.names { it.startsWith(Fss.DATA) || it == Fss.KEYS }

    /**
     * Step 3: byte copy of the flutter_secure_storage files (ciphertext only) before any ESP/Tink
     * call. `FlutterSharedPreferences.xml` is never copied (it may hold E0 plaintext keys).
     * Existing snapshot files are not overwritten (first attempt wins).
     */
    fun snapshotSecureStorage() {
        val dir = snapshotDir.apply { mkdirs() }
        for (name in secureStorageFileNames()) {
            for (src in listOf(prefsFiles.file(name), prefsFiles.backupFile(name))) {
                if (!src.isFile) continue
                val dst = File(dir, src.name)
                if (!dst.exists()) src.copyTo(dst)
            }
        }
    }

    /** Step 9: removes `InternalHomeWidgetPreferences` (Dart callback handles, meaningless natively). */
    fun deleteInternalHomeWidgetPrefs() {
        context.deleteSharedPreferences(INTERNAL_HOME_WIDGET)
    }

    /**
     * Copies the image_picker file behind `local_profile_picture` into `filesDir/profile/` (§4.9).
     * Returns the new absolute path, or `null` if the file is gone or outside the app's storage.
     */
    fun copyProfilePicture(path: String): String? = runCatching {
        val src = File(path).canonicalFile
        val allowedRoots = listOfNotNull(context.dataDir, context.cacheDir, context.filesDir, context.externalCacheDir)
            .map { it.canonicalPath + File.separator }
        if (!src.isFile || allowedRoots.none { src.path.startsWith(it) }) return@runCatching null
        val ext = src.extension.lowercase().takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) } ?: "jpg"
        val dir = File(context.filesDir, "profile").apply { mkdirs() }
        val dst = File(dir, "local_picture.$ext")
        src.copyTo(dst, overwrite = true)
        dst.absolutePath
    }.getOrNull()

    /**
     * Cleanup / reset: deletes every Flutter storage file (prefs, secure storage, wrapped keys,
     * config), google_fonts downloads, `app_flutter/`, plugin cache dirs and the snapshot.
     * `HomeWidgetPreferences` and `e21_widget` stay (they are the native widget stores).
     * Returns `false` if something could not be deleted.
     */
    fun deleteAllLegacyFiles(): Boolean {
        var ok = true
        val prefNames = prefsFiles.names {
            it == FlutterPrefs.FILE || it.startsWith(Fss.DATA) || it == Fss.KEYS || it == INTERNAL_HOME_WIDGET
        }
        for (name in prefNames) {
            context.deleteSharedPreferences(name)
            for (f in listOf(prefsFiles.file(name), prefsFiles.backupFile(name))) if (f.exists() && !f.delete()) ok = false
        }
        // google_fonts cached font files in the support directory (= filesDir). The native app bundles its fonts.
        context.filesDir.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".ttf", true) || it.name.endsWith(".otf", true)) }
            .forEach { if (!it.delete()) ok = false }
        val dirs = listOf(
            File(context.dataDir, "app_flutter"),
            File(context.cacheDir, "share_plus"),
            File(context.cacheDir, "file_picker"),
            File(context.cacheDir, "just_audio_cache"),
            snapshotDir,
        )
        for (d in dirs) if (d.exists() && !d.deleteRecursively()) ok = false
        return ok
    }

    companion object {
        const val INTERNAL_HOME_WIDGET = "InternalHomeWidgetPreferences"
    }
}
