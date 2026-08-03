package eu.kanade.tachiyomi.extension.all.smblibrary

import com.hierynomus.protocol.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SmbLibraryLogicTest {
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
    fun pageCodecRoundTripsRemoteZipOffsets() {
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
            archiveEntryOffset = 456,
            archiveCompressedSize = 789,
            archiveCompressionMethod = 8,
            archiveFlags = 2048,
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
    fun zipDirectoryAndEntryUseRandomAccessRanges() {
        val firstPage = ByteArray(512 * 1024).also { Random(7).nextBytes(it) }
        val secondPage = "second page".toByteArray()
        val archive = zipOf(
            "10.png" to firstPage,
            "2.png" to secondPage,
            "notes.txt" to "ignored".toByteArray(),
        )
        val data = ByteArrayRandomAccessData(archive)

        val entries = ZipDirectoryParser.read(data, "chapter.cbz")
            .filter { ContentDetector.isReadableZipImage(it.name, it.isDirectory) }
        assertEquals(listOf("10.png", "2.png"), entries.map { it.name })
        assertTrue(data.bytesRead < archive.size)

        val second = entries.single { it.name == "2.png" }
        data.bytesRead = 0
        val decoded = ZipEntryStream.open(data, second, "chapter.cbz").use { it.readBytes() }
        assertEquals(secondPage.toList(), decoded.toList())
        assertTrue(data.bytesRead < firstPage.size / 10)
    }

    @Test
    fun zipEntryStreamInflatesDeflatedUtf8Entry() {
        val expected = "漫画ページ-日本語".repeat(200).toByteArray()
        val archive = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("第1話/001.png"))
                zip.write(expected)
                zip.closeEntry()
            }
            output.toByteArray()
        }
        val data = ByteArrayRandomAccessData(archive)
        val entry = ZipDirectoryParser.read(data, "chapter.cbz").single()

        assertEquals("第1話/001.png", entry.name)
        assertEquals(expected.toList(), ZipEntryStream.open(data, entry, "chapter.cbz").use { it.readBytes() }.toList())
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

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                val crc = CRC32().apply { update(bytes) }
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        this.crc = crc.value
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private class ByteArrayRandomAccessData(
        private val bytes: ByteArray,
    ) : RandomAccessData {
        override val size = bytes.size.toLong()
        var bytesRead = 0

        override fun read(offset: Long, target: ByteArray, targetOffset: Int, length: Int): Int {
            if (offset >= bytes.size) return -1
            val count = minOf(length, bytes.size - offset.toInt())
            bytes.copyInto(target, targetOffset, offset.toInt(), offset.toInt() + count)
            bytesRead += count
            return count
        }

        override fun close() = Unit
    }

}
