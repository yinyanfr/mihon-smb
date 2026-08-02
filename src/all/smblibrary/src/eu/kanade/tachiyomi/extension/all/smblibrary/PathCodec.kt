package eu.kanade.tachiyomi.extension.all.smblibrary

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

object PathCodec {
    private const val BASE = "https://smb.library.local"

    fun normalizeRoot(path: String): String = normalizeRelativePath(path, allowEmpty = true)

    fun normalizeRelativePath(path: String, allowEmpty: Boolean = false): String {
        val normalized = path.replace('\\', '/').trim('/')
        if (normalized.isEmpty()) {
            if (allowEmpty) return ""
            throw SmbLibraryException.UnsafePath(path)
        }
        if (!isSafeRelativePath(normalized)) throw SmbLibraryException.UnsafePath(path)
        return normalized
    }

    fun isSafeRelativePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.contains('\u0000')) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return false
        return normalized.split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".."
        }
    }

    fun join(left: String, right: String): String {
        val cleanLeft = normalizeRelativePath(left, allowEmpty = true)
        val cleanRight = normalizeRelativePath(right)
        return if (cleanLeft.isEmpty()) cleanRight else "$cleanLeft/$cleanRight"
    }

    fun mangaUrl(relativePath: String): String = "$BASE/manga?path=${enc(normalizeRelativePath(relativePath))}"

    fun chapterUrl(descriptor: ChapterDescriptor): String = "$BASE/chapter" +
        "?manga=${enc(descriptor.mangaPath)}" +
        "&type=${enc(descriptor.type.id)}" +
        "&path=${enc(descriptor.chapterPath)}" +
        "&size=${descriptor.size}" +
        "&mtime=${descriptor.lastModifiedMillis}"

    fun pageUrl(descriptor: PageDescriptor): String = "$BASE/page" +
        "?manga=${enc(descriptor.mangaPath)}" +
        "&chapter=${enc(descriptor.chapterPath)}" +
        "&type=${enc(descriptor.type.id)}" +
        "&path=${enc(descriptor.pagePath)}" +
        "&index=${descriptor.index}" +
        "&size=${descriptor.size}" +
        "&mtime=${descriptor.lastModifiedMillis}" +
        "&archiveSize=${descriptor.archiveSize}" +
        "&archiveMtime=${descriptor.archiveLastModifiedMillis}"

    fun thumbnailUrl(mangaPath: String, lastModifiedMillis: Long): String = "$BASE/thumbnail" +
        "?manga=${enc(normalizeRelativePath(mangaPath))}" +
        "&mtime=$lastModifiedMillis"

    fun mangaPath(url: String): String = normalizeRelativePath(params(url).getValue("path"))

    fun thumbnail(url: String): ThumbnailDescriptor {
        val params = params(url)
        return ThumbnailDescriptor(
            mangaPath = normalizeRelativePath(params.getValue("manga")),
            lastModifiedMillis = params.getValue("mtime").toLong(),
        )
    }

    fun chapter(url: String): ChapterDescriptor {
        val params = params(url)
        val manga = normalizeRelativePath(params.getValue("manga"))
        val path = normalizeRelativePath(params.getValue("path"), allowEmpty = true)
        return ChapterDescriptor(
            mangaPath = manga,
            type = ChapterType.fromId(params.getValue("type")),
            chapterPath = path,
            name = path.substringAfterLast('/').ifEmpty { "本卷" },
            size = params.getValue("size").toLong(),
            lastModifiedMillis = params.getValue("mtime").toLong(),
        )
    }

    fun page(url: String): PageDescriptor {
        val params = params(url)
        return PageDescriptor(
            type = PageType.fromId(params.getValue("type")),
            mangaPath = normalizeRelativePath(params.getValue("manga")),
            chapterPath = normalizeRelativePath(params.getValue("chapter"), allowEmpty = true),
            pagePath = normalizeRelativePath(params.getValue("path")),
            index = params.getValue("index").toInt(),
            size = params.getValue("size").toLong(),
            lastModifiedMillis = params.getValue("mtime").toLong(),
            archiveSize = params["archiveSize"]?.toLongOrNull() ?: 0L,
            archiveLastModifiedMillis = params["archiveMtime"]?.toLongOrNull() ?: 0L,
        )
    }

    fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun params(url: String): Map<String, String> {
        val query = url.substringAfter('?', missingDelimiterValue = "")
        return query.split('&')
            .filter { it.isNotBlank() }
            .associate {
                val key = it.substringBefore('=')
                val value = it.substringAfter('=', missingDelimiterValue = "")
                dec(key) to dec(value)
            }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun dec(value: String): String = URLDecoder.decode(value, "UTF-8")
}
