package space.einundzwanzig.meetup.core.data.file

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.data.internal.DataLog
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [FileStore] below the directory returned by [rootProvider] (resolved lazily, so injecting the
 * store does not touch the disk); see the interface for the contract.
 */
internal class DirectoryFileStore(
    rootProvider: () -> File,
    private val ioDispatcher: CoroutineDispatcher,
) : FileStore {

    private val root: File by lazy(rootProvider)

    override suspend fun readBytes(path: String): ByteArray? {
        val file = resolve(path)
        return withContext(ioDispatcher) {
            try {
                if (file.isFile) file.readBytes() else null
            } catch (e: FileNotFoundException) {
                null
            } catch (e: IOException) {
                DataLog.e(TAG, "Reading '$path' failed", e)
                null
            }
        }
    }

    override suspend fun writeBytes(path: String, bytes: ByteArray): Boolean {
        val target = resolve(path)
        return withContext(ioDispatcher) {
            var temp: File? = null
            try {
                val dir = target.parentFile ?: throw IOException("No parent directory")
                if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create directory")
                temp = File.createTempFile(TEMP_PREFIX + target.name + ".", TEMP_SUFFIX, dir)
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                temp = null
                true
            } catch (e: IOException) {
                DataLog.e(TAG, "Writing '$path' failed", e)
                false
            } finally {
                temp?.delete()
            }
        }
    }

    override suspend fun delete(path: String): Boolean {
        val file = resolve(path)
        return withContext(ioDispatcher) {
            val ok = !file.exists() || file.delete()
            if (!ok) DataLog.e(TAG, "Deleting '$path' failed")
            ok
        }
    }

    override suspend fun exists(path: String): Boolean {
        val file = resolve(path)
        return withContext(ioDispatcher) { file.isFile }
    }

    override suspend fun list(directory: String): List<String> {
        val dir = if (directory.isEmpty()) root else resolve(directory)
        return withContext(ioDispatcher) {
            dir.listFiles()
                ?.filter { it.isFile && !isTempFile(it.name) }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()
        }
    }

    override fun resolve(path: String): File {
        require(path.isNotEmpty()) { "Empty path" }
        require(!path.startsWith("/") && !path.contains('\\')) { "Path must be relative and '/'-separated" }
        val segments = path.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid path segment" }
        return segments.fold(root) { dir, segment -> File(dir, segment) }
    }

    private fun isTempFile(name: String) = name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX)

    private companion object {
        const val TAG = "FileStore"
        const val TEMP_PREFIX = ".fs-"
        const val TEMP_SUFFIX = ".tmp"
    }
}
