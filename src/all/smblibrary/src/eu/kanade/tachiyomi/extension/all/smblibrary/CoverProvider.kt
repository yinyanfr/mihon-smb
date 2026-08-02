package eu.kanade.tachiyomi.extension.all.smblibrary

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.util.zip.ZipInputStream

class CoverProvider(
    private val repository: SmbRepository,
) {
    fun open(config: SmbConfig, mangaPath: String): CoverHandle? {
        val candidate = repository.browse(config) {
            val entries = list(mangaPath)
            CoverCandidateSelector.firstRootImage(entries)
                ?: CoverCandidateSelector.imageFolders(entries).firstNotNullOfOrNull { folder ->
                    CoverCandidateSelector.firstRootImage(list(folder.relativePath))
                }
                ?: CoverCandidateSelector.archives(entries).firstOrNull()
        }
        return when {
            candidate == null -> null
            ContentDetector.isArchive(candidate.name) -> openFirstArchiveImage(config, candidate)
            else -> openRemoteImage(config, candidate)
        }
    }

    private fun openRemoteImage(config: SmbConfig, image: RemoteEntry): CoverHandle {
        val remote = repository.openFile(config, image.relativePath)
        return CoverHandle(
            path = image.relativePath,
            length = image.size,
            inputStream = remote.inputStream,
            closeable = remote,
        )
    }

    private fun openFirstArchiveImage(config: SmbConfig, archive: RemoteEntry): CoverHandle? {
        val remote = repository.openFile(config, archive.relativePath)
        return ArchiveCoverReader.open(remote)
    }
}

object ArchiveCoverReader {
    fun open(remote: RemoteFileHandle): CoverHandle? {
        val zip = ZipInputStream(BufferedInputStream(remote.inputStream, ZIP_BUFFER_SIZE))
        try {
            while (true) {
                val entry = zip.nextEntry
                if (entry == null) {
                    closeArchive(zip, remote)
                    return null
                }
                if (ContentDetector.isReadableZipImage(entry)) {
                    return CoverHandle(
                        path = entry.name,
                        length = entry.size,
                        inputStream = zip,
                        closeable = Closeable {
                            closeArchive(zip, remote)
                        },
                    )
                }
                zip.closeEntry()
            }
        } catch (e: Throwable) {
            runCatching { closeArchive(zip, remote) }
                .exceptionOrNull()
                ?.let(e::addSuppressed)
            throw e
        }
    }

    private fun closeArchive(zip: ZipInputStream, remote: RemoteFileHandle) {
        try {
            zip.close()
        } finally {
            remote.close()
        }
    }

    private companion object {
        const val ZIP_BUFFER_SIZE = 128 * 1024
    }
}

object CoverCandidateSelector {
    fun firstRootImage(entries: Iterable<RemoteEntry>): RemoteEntry? = NaturalSort.sortedBy(
        entries.filter { !it.isDirectory && ContentDetector.isSupportedImage(it.name) },
    ) { it.name }.firstOrNull()

    fun imageFolders(entries: Iterable<RemoteEntry>): List<RemoteEntry> = NaturalSort.sortedBy(
        entries.filter { it.isDirectory },
    ) { it.name }

    fun archives(entries: Iterable<RemoteEntry>): List<RemoteEntry> = NaturalSort.sortedBy(
        entries.filter { !it.isDirectory && ContentDetector.isArchive(it.name) },
    ) { it.name }
}

class CoverHandle(
    val path: String,
    val length: Long,
    val inputStream: InputStream,
    private val closeable: Closeable,
) : Closeable {
    override fun close() = closeable.close()
}
