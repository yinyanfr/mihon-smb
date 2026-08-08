package eu.kanade.tachiyomi.extension.all.smblibrary

import com.hierynomus.protocol.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.zip.ZipEntry

class SmbLibraryLogicTest {
    @Test
    fun bundledCoverUsesTheSourcesInterceptedHttpOrigin() {
        val uri = URI(BundledCover.URL)

        assertEquals("https", uri.scheme)
        assertEquals(BundledCover.HOST, uri.host)
        assertEquals(BundledCover.PATH, uri.path)
    }

    @Test
    fun naturalSortOrdersNumbers() {
        assertEquals(
            listOf("1", "2", "9", "10"),
            NaturalSort.sorted(listOf("10", "2", "1", "9")),
        )
    }

    @Test
    fun naturalSortHandlesMixedChapterNamesAndFullWidthDigits() {
        assertEquals(
            listOf("001", "１０", "chapter 3", "Chapter 10", "第1話", "第02話"),
            NaturalSort.sorted(listOf("Chapter 10", "第02話", "chapter 3", "第1話", "１０", "001")),
        )
    }

    @Test
    fun imageExtensionDetectionIsCaseInsensitive() {
        assertTrue(ContentDetector.isSupportedImage("A/001.JPG"))
        assertTrue(ContentDetector.isSupportedImage("A/002.WebP"))
        assertFalse(ContentDetector.isSupportedImage("A/notes.txt"))
    }

    @Test
    fun archiveExtensionDetectionIsCaseInsensitive() {
        assertTrue(ContentDetector.isArchive("chapter 1.CBZ"))
        assertTrue(ContentDetector.isArchive("chapter 2.Zip"))
        assertFalse(ContentDetector.isArchive("chapter 3.rar"))
    }

    @Test
    fun rootMangaFileDetectionIncludesPdfWithoutMakingItAnArchive() {
        assertTrue(ContentDetector.isRootMangaFile("standalone.CBZ"))
        assertTrue(ContentDetector.isRootMangaFile("standalone.ZIP"))
        assertTrue(ContentDetector.isRootMangaFile("standalone.PDF"))
        assertFalse(ContentDetector.isArchive("standalone.PDF"))
        assertFalse(ContentDetector.isRootMangaFile("notes.txt"))
    }

    @Test
    fun pathCodecRoundTripsSpecialCharacters() {
        val original = "Special Characters 漫画 [作者] #1/第01話 100% 😀"
        val url = PathCodec.mangaUrl(original)
        assertEquals(original, PathCodec.mangaPath(url))
    }

    @Test
    fun pathCodecRoundTripsJapaneseNames() {
        val chapter = ChapterDescriptor(
            mangaPath = "漫画/作品",
            type = ChapterType.ImageDirectory,
            chapterPath = "漫画/作品/第1話",
            name = "第1話",
            size = 10,
            lastModifiedMillis = 20,
        )
        assertEquals(chapter, PathCodec.chapter(PathCodec.chapterUrl(chapter)))
    }

    @Test
    fun rootFilesMangaUsesASeparateStableUrl() {
        val url = PathCodec.rootFilesMangaUrl()

        assertTrue(PathCodec.isRootFilesManga(url))
        assertFalse(PathCodec.isRootFilesManga(PathCodec.mangaUrl("others")))
    }

    @Test
    fun pageCodecRoundTripsArchiveFingerprint() {
        val page = PageDescriptor(
            type = PageType.ArchiveEntry,
            mangaPath = "漫画 [作者]",
            chapterPath = "漫画 [作者]/第1話.cbz",
            pagePath = "images/001 %.png",
            index = 0,
            size = 1234,
            lastModifiedMillis = 100,
            archiveSize = 9999,
            archiveLastModifiedMillis = 200,
        )

        assertEquals(page, PathCodec.page(PathCodec.pageUrl(page)))
    }

    @Test
    fun pathCodecRejectsTraversal() {
        assertThrows(SmbLibraryException.UnsafePath::class.java) {
            PathCodec.mangaUrl("../secret")
        }
    }

    @Test
    fun zipEntryFilterIgnoresMetadata() {
        assertFalse(ContentDetector.isReadableZipImage(ZipEntry("__MACOSX/001.jpg")))
        assertFalse(ContentDetector.isReadableZipImage(ZipEntry("folder/.DS_Store")))
        assertFalse(ContentDetector.isReadableZipImage(ZipEntry("folder/info.txt")))
        assertTrue(ContentDetector.isReadableZipImage(ZipEntry("folder/001.png")))
    }

    @Test
    fun zipEntryFilterRejectsZipSlip() {
        assertFalse(ContentDetector.isReadableZipImage(ZipEntry("../001.jpg")))
    }

    @Test
    fun cacheFingerprintChangesWithServerOrRemoteFile() {
        val first = ArchiveFingerprint("nas-a", "A/chapter.cbz", 100, 1)
        val second = ArchiveFingerprint("nas-a", "A/chapter.cbz", 101, 1)
        val third = ArchiveFingerprint("nas-a", "A/chapter.cbz", 100, 2)
        val fourth = ArchiveFingerprint("nas-b", "A/chapter.cbz", 100, 1)

        assertNotEquals(first.cacheKey, second.cacheKey)
        assertNotEquals(first.cacheKey, third.cacheKey)
        assertNotEquals(first.cacheKey, fourth.cacheKey)
    }

    @Test
    fun looseRootFilesBecomeOthersUsingNewestModificationTime() {
        val entries = listOf(
            RemoteEntry("Manga", "Manga", true, 0, 500),
            RemoteEntry("old.cbz", "old.cbz", false, 100, 100),
            RemoteEntry("new.PDF", "new.PDF", false, 200, 300),
            RemoteEntry("middle.zip", "middle.zip", false, 150, 200),
            RemoteEntry("notes.txt", "notes.txt", false, 999, 900),
        )

        val others = RootFilesManga.listingEntry(entries)!!

        assertEquals("others", others.name)
        assertEquals(450L, others.size)
        assertEquals(300L, others.lastModifiedMillis)
        assertFalse(others.isDirectory)
    }

    @Test
    fun othersListsOnlyReadableArchivesAsChapters() {
        val entries = listOf(
            RemoteEntry("chapter 10.cbz", "chapter 10.cbz", false, 100, 10),
            RemoteEntry("reference.pdf", "reference.pdf", false, 200, 20),
            RemoteEntry("chapter 2.ZIP", "chapter 2.ZIP", false, 300, 30),
        )

        val chapters = RootFilesManga.archiveChapters(entries)

        assertEquals(listOf("chapter 10.cbz", "chapter 2.ZIP"), chapters.map { it.name })
        assertTrue(chapters.all { it.mangaPath == RootFilesManga.INTERNAL_PATH })
    }

    @Test
    fun rootImagesMapToSingleVirtualChapterDescriptor() {
        val entries = listOf(
            RemoteEntry("Manga/1.jpg", "1.jpg", false, 1, 10),
            RemoteEntry("Manga/2.jpg", "2.jpg", false, 1, 11),
            RemoteEntry("Manga/notes.txt", "notes.txt", false, 1, 12),
        )
        val rootImages = entries.filter { !it.isDirectory && ContentDetector.isSupportedImage(it.name) }
        val chapter = ChapterDescriptor("Manga", ChapterType.RootImages, "", "本卷", rootImages.sumOf { it.size }, rootImages.maxOf { it.lastModifiedMillis })
        assertEquals("本卷", chapter.name)
        assertEquals(ChapterType.RootImages, chapter.type)
        assertEquals(2, rootImages.size)
    }

    @Test
    fun mixedChapterDescriptorsSortNaturally() {
        val chapters = listOf(
            ChapterDescriptor("M", ChapterType.Archive, "M/chapter 10.cbz", "chapter 10.cbz", 1, 1),
            ChapterDescriptor("M", ChapterType.ImageDirectory, "M/第1話", "第1話", 1, 1),
            ChapterDescriptor("M", ChapterType.Archive, "M/chapter 2.cbz", "chapter 2.cbz", 1, 1),
            ChapterDescriptor("M", ChapterType.RootImages, "", "本卷", 1, 1),
        )
        assertEquals(
            listOf("chapter 2.cbz", "chapter 10.cbz", "本卷", "第1話"),
            NaturalSort.sortedBy(chapters) { it.name }.map { it.name },
        )
    }

    @Test
    fun chapterDescriptorsSortNaturallyDescendingForDisplay() {
        val chapters = listOf(
            ChapterDescriptor("M", ChapterType.ImageDirectory, "M/1", "1", 1, 1),
            ChapterDescriptor("M", ChapterType.ImageDirectory, "M/10", "10", 1, 1),
            ChapterDescriptor("M", ChapterType.ImageDirectory, "M/2", "2", 1, 1),
        )
        assertEquals(
            listOf("10", "2", "1"),
            NaturalSort.sortedBy(chapters) { it.name }.asReversed().map { it.name },
        )
    }

    @Test
    fun mangaListingDefaultsToLastModifiedDescending() {
        val entries = listOf(
            RemoteEntry("old", "old", true, 0, 100),
            RemoteEntry("newest", "newest", true, 0, 300),
            RemoteEntry("newer", "newer", true, 0, 200),
        )

        assertEquals(
            listOf("newest", "newer", "old"),
            MangaListingSorter.sorted(entries, MangaSort.DEFAULT).map { it.name },
        )
    }

    @Test
    fun mangaListingSupportsNaturalNameInBothDirections() {
        val entries = listOf(
            RemoteEntry("10", "10", true, 0, 100),
            RemoteEntry("2", "2", true, 0, 300),
            RemoteEntry("1", "1", true, 0, 200),
        )

        assertEquals(
            listOf("1", "2", "10"),
            MangaListingSorter.sorted(entries, MangaSort(MangaSortField.Name, ascending = true)).map { it.name },
        )
        assertEquals(
            listOf("10", "2", "1"),
            MangaListingSorter.sorted(entries, MangaSort(MangaSortField.Name, ascending = false)).map { it.name },
        )
    }

    @Test
    fun mangaListingSupportsLastModifiedAscendingWithStableNameTieBreak() {
        val entries = listOf(
            RemoteEntry("same-10", "same 10", true, 0, 200),
            RemoteEntry("old", "old", true, 0, 100),
            RemoteEntry("same-2", "same 2", true, 0, 200),
        )

        assertEquals(
            listOf("old", "same 2", "same 10"),
            MangaListingSorter.sorted(entries, MangaSort(MangaSortField.LastModified, ascending = true)).map { it.name },
        )
    }

    @Test
    fun archiveCleanupKeepsProtectedOversizedFile() {
        val directory = Files.createTempDirectory("smb-library-cache-test").toFile()
        try {
            val protected = File(directory, "protected.zip").apply { writeBytes(ByteArray(20)) }
            val old = File(directory, "old.zip").apply { writeBytes(ByteArray(8)) }
            old.setLastModified(1)
            protected.setLastModified(2)

            ArchiveCache(SmbRepository(), maxBytes = { 10 }, archiveDirectory = directory).cleanup(protected)

            assertTrue(protected.exists())
            assertFalse(old.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun archiveDownloadValidatorRejectsChangedRemoteFile() {
        val fingerprint = ArchiveFingerprint("nas", "M/chapter.cbz", 100, 10)
        val changed = RemoteEntry("M/chapter.cbz", "chapter.cbz", false, 101, 11)

        assertThrows(SmbLibraryException.ZipDownloadInterrupted::class.java) {
            ArchiveDownloadValidator.validate(fingerprint, downloadedSize = 101, current = changed)
        }
    }

    @Test
    fun archiveDownloadValidatorAcceptsMatchingFingerprint() {
        val fingerprint = ArchiveFingerprint("nas", "M/chapter.cbz", 100, 10)
        val current = RemoteEntry("M/chapter.cbz", "chapter.cbz", false, 100, 10)

        ArchiveDownloadValidator.validate(fingerprint, downloadedSize = 100, current = current)
    }

    @Test
    fun smbErrorsDistinguishConnectionFailuresAndReadFailures() {
        val repository = SmbRepository()

        assertTrue(repository.translate("M", UnknownHostException()) is SmbLibraryException.HostUnreachable)
        assertTrue(repository.translate("M", SocketTimeoutException()) is SmbLibraryException.Timeout)
        assertTrue(repository.translate("M", ConnectException()) is SmbLibraryException.TcpConnectionFailed)
        assertTrue(repository.translate("M", NoRouteToHostException()) is SmbLibraryException.TcpConnectionFailed)
        assertTrue(repository.translate("M", TransportException(UnknownHostException())) is SmbLibraryException.HostUnreachable)
        assertTrue(repository.translate("M", IOException()) is SmbLibraryException.ReadDisconnected)
    }

}
