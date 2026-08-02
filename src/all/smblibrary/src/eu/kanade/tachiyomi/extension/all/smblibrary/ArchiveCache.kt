package eu.kanade.tachiyomi.extension.all.smblibrary

import keiyoushi.utils.applicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipException
import java.util.zip.ZipFile

class ArchiveCache(
    private val repository: SmbRepository,
    private val maxBytes: Long = 512L * 1024L * 1024L,
    private val archiveDirectory: File? = null,
) {
    private val archiveDir: File by lazy {
        (archiveDirectory ?: File(applicationContext.cacheDir, "smb-library/archives")).also { it.mkdirs() }
    }
    private val locks = ConcurrentHashMap<String, Any>()
    private val cleanupLock = Any()

    fun getOrDownload(
        config: SmbConfig,
        fingerprint: ArchiveFingerprint,
    ): File {
        val lock = locks.getOrPut(fingerprint.cacheKey) { Any() }
        synchronized(lock) {
            val target = cacheFile(fingerprint)
            if (target.exists() && target.length() == fingerprint.size) {
                target.setLastModified(System.currentTimeMillis())
                return target
            }

            archiveDir.mkdirs()
            val tmp = File(archiveDir, "${fingerprint.cacheKey}.tmp")
            tmp.delete()
            try {
                repository.openFile(config, fingerprint.relativePath).use { remote ->
                    tmp.outputStream().use { output ->
                        remote.inputStream.use { input ->
                            val downloadedSize = input.copyTo(output, DOWNLOAD_BUFFER_SIZE)
                            val current = repository.metadata(config, fingerprint.relativePath)
                            ArchiveDownloadValidator.validate(fingerprint, downloadedSize, current)
                        }
                    }
                }
                if (target.exists() && !target.delete()) {
                    throw IOException("Unable to replace old archive cache")
                }
                if (!tmp.renameTo(target)) {
                    throw IOException("Unable to move archive cache into place")
                }
                target.setLastModified(System.currentTimeMillis())
                cleanup(target)
                return target
            } catch (e: IOException) {
                tmp.delete()
                if (e.message?.contains("space", ignoreCase = true) == true) {
                    throw SmbLibraryException.CacheFull(e)
                }
                throw SmbLibraryException.ZipDownloadInterrupted(fingerprint.relativePath, e)
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        }
    }

    fun listImageEntries(archiveFile: File, archivePath: String): List<ArchivePageEntry> {
        try {
            ZipFile(archiveFile).use { zip ->
                return NaturalSort.sortedBy(
                    zip.entries().asSequence()
                        .filter(ContentDetector::isReadableZipImage)
                        .map {
                            ArchivePageEntry(
                                name = it.name,
                                size = it.size,
                                lastModifiedMillis = it.time.coerceAtLeast(0L),
                            )
                        }
                        .toList(),
                ) { it.name }
            }
        } catch (e: ZipException) {
            if (e.message?.contains("encrypt", ignoreCase = true) == true) {
                throw SmbLibraryException.ZipEncrypted(archivePath, e)
            }
            throw SmbLibraryException.ZipBroken(archivePath, e)
        } catch (e: IOException) {
            throw SmbLibraryException.ZipBroken(archivePath, e)
        }
    }

    fun openEntry(archiveFile: File, entryName: String, archivePath: String): ZipEntryHandle {
        if (!PathCodec.isSafeRelativePath(entryName)) throw SmbLibraryException.UnsafePath(entryName)
        var zip: ZipFile? = null
        try {
            zip = ZipFile(archiveFile)
            val entry = zip.getEntry(entryName)
                ?: throw SmbLibraryException.FileRemoved("$archivePath!/$entryName")
            if (!ContentDetector.isReadableZipImage(entry)) throw SmbLibraryException.UnsupportedImage(entryName)
            val inputStream = zip.getInputStream(entry)
            return ZipEntryHandle(zip, inputStream).also {
                zip = null
            }
        } catch (e: ZipException) {
            if (e.message?.contains("encrypt", ignoreCase = true) == true) {
                throw SmbLibraryException.ZipEncrypted(archivePath, e)
            }
            throw SmbLibraryException.ZipBroken(archivePath, e)
        } catch (e: IOException) {
            if (e.message?.contains("encrypt", ignoreCase = true) == true) {
                throw SmbLibraryException.ZipEncrypted(archivePath, e)
            }
            throw SmbLibraryException.ZipBroken(archivePath, e)
        } finally {
            zip?.close()
        }
    }

    fun cacheFile(fingerprint: ArchiveFingerprint): File = File(archiveDir, "${fingerprint.cacheKey}.zip")

    internal fun cleanup(protectedFile: File) {
        synchronized(cleanupLock) {
            val files = archiveDir.listFiles { file -> file.isFile && file.extension == "zip" }.orEmpty()
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return

            files.asSequence()
                .filterNot { it.absolutePath == protectedFile.absolutePath }
                .sortedBy { it.lastModified() }
                .forEach { file ->
                    if (total <= maxBytes) return
                    val size = file.length()
                    if (file.delete()) total -= size
                }
        }
    }

    private companion object {
        const val DOWNLOAD_BUFFER_SIZE = 1024 * 1024
    }
}

object ArchiveDownloadValidator {
    fun validate(fingerprint: ArchiveFingerprint, downloadedSize: Long, current: RemoteEntry) {
        val changed = downloadedSize != fingerprint.size ||
            current.size != fingerprint.size ||
            current.lastModifiedMillis != fingerprint.lastModifiedMillis
        if (changed) {
            throw SmbLibraryException.ZipDownloadInterrupted(
                fingerprint.relativePath,
                IOException("Remote archive changed during download. Refresh the chapter list and try again."),
            )
        }
    }
}

data class ArchivePageEntry(
    val name: String,
    val size: Long,
    val lastModifiedMillis: Long,
)

class ZipEntryHandle(
    private val zipFile: ZipFile,
    val inputStream: java.io.InputStream,
) : java.io.Closeable {
    override fun close() {
        try {
            inputStream.close()
        } finally {
            zipFile.close()
        }
    }
}
