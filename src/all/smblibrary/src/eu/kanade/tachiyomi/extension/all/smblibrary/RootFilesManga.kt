package eu.kanade.tachiyomi.extension.all.smblibrary

object RootFilesManga {
    const val TITLE = "others"
    const val INTERNAL_PATH = "_smb_library_root_files_"

    fun listingEntry(entries: Iterable<RemoteEntry>): RemoteEntry? {
        val files = entries.filter { !it.isDirectory && ContentDetector.isRootMangaFile(it.name) }
        if (files.isEmpty()) return null
        return RemoteEntry(
            relativePath = "",
            name = TITLE,
            isDirectory = false,
            size = files.sumOf { it.size.coerceAtLeast(0L) },
            lastModifiedMillis = files.maxOf { it.lastModifiedMillis },
        )
    }

    fun archiveChapters(entries: Iterable<RemoteEntry>): List<ChapterDescriptor> = entries
        .filter { !it.isDirectory && ContentDetector.isArchive(it.name) }
        .map { archive ->
            ChapterDescriptor(
                mangaPath = INTERNAL_PATH,
                type = ChapterType.Archive,
                chapterPath = archive.relativePath,
                name = archive.name,
                size = archive.size,
                lastModifiedMillis = archive.lastModifiedMillis,
            )
        }
}
