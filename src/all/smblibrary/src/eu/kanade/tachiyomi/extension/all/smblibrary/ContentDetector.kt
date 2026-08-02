package eu.kanade.tachiyomi.extension.all.smblibrary

import java.util.Locale
import java.util.zip.ZipEntry

object ContentDetector {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val archiveExtensions = setOf("zip", "cbz")

    fun isSupportedImage(path: String): Boolean = extension(path) in imageExtensions

    fun isArchive(path: String): Boolean = extension(path) in archiveExtensions

    fun mimeType(path: String): String = when (extension(path)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

    fun isReadableZipImage(entry: ZipEntry): Boolean {
        if (entry.isDirectory) return false
        val name = entry.name.replace('\\', '/')
        if (!PathCodec.isSafeRelativePath(name)) return false
        val fileName = name.substringAfterLast('/')
        if (fileName == ".DS_Store") return false
        if (fileName.startsWith(".")) return false
        if (name.startsWith("__MACOSX/")) return false
        if ("/__MACOSX/" in name) return false
        return isSupportedImage(name)
    }

    fun extension(path: String): String {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        return fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
    }
}
