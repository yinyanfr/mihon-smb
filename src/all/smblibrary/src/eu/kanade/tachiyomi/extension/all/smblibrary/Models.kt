package eu.kanade.tachiyomi.extension.all.smblibrary

data class SmbConfig(
    val host: String,
    val port: Int,
    val share: String,
    val rootPath: String,
    val username: String,
    val password: String,
    val domain: String,
    val timeoutMillis: Long,
) {
    val isUsable: Boolean
        get() = host.isNotBlank() && port in 1..65535 && share.isNotBlank()

    val cacheNamespace: String
        get() = PathCodec.stableHash("${host.lowercase()}:$port|$share|$rootPath")
}

data class RemoteEntry(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModifiedMillis: Long,
)

enum class ChapterType(val id: String) {
    ImageDirectory("dir"),
    Archive("archive"),
    RootImages("root"),
    ;

    companion object {
        fun fromId(id: String): ChapterType = entries.first { it.id == id }
    }
}

data class ChapterDescriptor(
    val mangaPath: String,
    val type: ChapterType,
    val chapterPath: String,
    val name: String,
    val size: Long,
    val lastModifiedMillis: Long,
)

enum class PageType(val id: String) {
    RemoteImage("remote"),
    ArchiveEntry("archive-entry"),
    ;

    companion object {
        fun fromId(id: String): PageType = entries.first { it.id == id }
    }
}

data class PageDescriptor(
    val type: PageType,
    val mangaPath: String,
    val chapterPath: String,
    val pagePath: String,
    val index: Int,
    val size: Long,
    val lastModifiedMillis: Long,
    val archiveSize: Long = 0L,
    val archiveLastModifiedMillis: Long = 0L,
)

data class ArchiveFingerprint(
    val cacheNamespace: String,
    val relativePath: String,
    val size: Long,
    val lastModifiedMillis: Long,
) {
    val cacheKey: String
        get() = PathCodec.stableHash("$cacheNamespace|$relativePath|$size|$lastModifiedMillis")
}

sealed class SmbLibraryException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : SmbLibraryException("SMB is not configured. Fill in Host, Port and Share in extension settings.")
    class HostUnreachable(cause: Throwable) : SmbLibraryException("Unable to resolve or reach SMB host.", cause)
    class TcpConnectionFailed(cause: Throwable) : SmbLibraryException("Unable to connect to SMB port.", cause)
    class AuthenticationFailed(cause: Throwable) : SmbLibraryException("SMB login failed. Check username, password and domain.", cause)
    class ShareMissing(cause: Throwable) : SmbLibraryException("SMB share does not exist or cannot be opened.", cause)
    class PathMissing(path: String, cause: Throwable? = null) : SmbLibraryException("SMB path does not exist: $path", cause)
    class AccessDenied(path: String, cause: Throwable) : SmbLibraryException("No permission to access SMB path: $path", cause)
    class Timeout(cause: Throwable) : SmbLibraryException("SMB operation timed out.", cause)
    class FileRemoved(path: String, cause: Throwable? = null) : SmbLibraryException("File was removed after listing: $path", cause)
    class ZipDownloadInterrupted(path: String, cause: Throwable) : SmbLibraryException("ZIP download was interrupted: $path", cause)
    class ZipBroken(path: String, cause: Throwable) : SmbLibraryException("ZIP/CBZ is broken or unreadable: $path", cause)
    class ZipEncrypted(path: String, cause: Throwable) : SmbLibraryException("Encrypted ZIP/CBZ archives are not supported: $path", cause)
    class UnsupportedImage(path: String) : SmbLibraryException("Unsupported image format: $path")
    class ReadDisconnected(path: String, cause: Throwable) : SmbLibraryException("SMB connection was interrupted while reading: $path", cause)
    class CacheFull(cause: Throwable) : SmbLibraryException("Not enough local cache space for archive.", cause)
    class UnsafePath(path: String) : SmbLibraryException("Unsafe path rejected: $path")
}
