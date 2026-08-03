package eu.kanade.tachiyomi.extension.all.smblibrary

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

interface RandomAccessData : Closeable {
    val size: Long

    fun read(offset: Long, target: ByteArray, targetOffset: Int, length: Int): Int
}

class RemoteZipReader(
    private val repository: SmbRepository,
) {
    fun listImageEntries(config: SmbConfig, fingerprint: ArchiveFingerprint): List<ArchivePageEntry> = repository.openRandomAccessFile(config, fingerprint.relativePath).use { archive ->
        validateFingerprint(archive, fingerprint)
        NaturalSort.sortedBy(
            ZipDirectoryParser.read(archive, fingerprint.relativePath)
                .filter { ContentDetector.isReadableZipImage(it.name, it.isDirectory) },
        ) { it.name }
    }

    fun openEntry(config: SmbConfig, descriptor: PageDescriptor): ZipEntryHandle {
        if (!PathCodec.isSafeRelativePath(descriptor.pagePath)) {
            throw SmbLibraryException.UnsafePath(descriptor.pagePath)
        }
        if (descriptor.archiveFlags and ENCRYPTED_FLAG != 0) {
            throw SmbLibraryException.ZipEncrypted(
                descriptor.chapterPath,
                IOException("Encrypted ZIP entry: ${descriptor.pagePath}"),
            )
        }

        val archive = repository.openRandomAccessFile(config, descriptor.chapterPath)
        try {
            if (
                archive.size != descriptor.archiveSize ||
                archive.lastModifiedMillis != descriptor.archiveLastModifiedMillis
            ) {
                throw SmbLibraryException.ZipChanged(descriptor.chapterPath)
            }

            val entry = ArchivePageEntry(
                name = descriptor.pagePath,
                size = descriptor.size,
                compressedSize = descriptor.archiveCompressedSize,
                localHeaderOffset = descriptor.archiveEntryOffset,
                compressionMethod = descriptor.archiveCompressionMethod,
                flags = descriptor.archiveFlags,
                lastModifiedMillis = descriptor.lastModifiedMillis,
                isDirectory = false,
            )
            val input = ZipEntryStream.open(archive, entry, descriptor.chapterPath)
            return ZipEntryHandle(input, archive)
        } catch (e: Throwable) {
            archive.close()
            throw e
        }
    }

    private fun validateFingerprint(archive: RemoteRandomAccessHandle, fingerprint: ArchiveFingerprint) {
        if (archive.size != fingerprint.size || archive.lastModifiedMillis != fingerprint.lastModifiedMillis) {
            throw SmbLibraryException.ZipChanged(fingerprint.relativePath)
        }
    }

    private companion object {
        const val ENCRYPTED_FLAG = 1
    }
}

internal object ZipDirectoryParser {
    private const val EOCD_SIGNATURE = 0x06054B50L
    private const val ZIP64_EOCD_SIGNATURE = 0x06064B50L
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064B50L
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014B50L
    private const val MAX_EOCD_SIZE = 65_557
    private const val MAX_CENTRAL_DIRECTORY_SIZE = 64L * 1024L * 1024L
    private val cp437 = runCatching { Charset.forName("CP437") }.getOrDefault(Charsets.UTF_8)

    fun read(data: RandomAccessData, archivePath: String): List<ArchivePageEntry> {
        try {
            val directory = locateDirectory(data)
            if (directory.size > MAX_CENTRAL_DIRECTORY_SIZE || directory.size > Int.MAX_VALUE) {
                throw IOException("ZIP central directory is too large")
            }
            val bytes = data.readFully(directory.offset, directory.size.toInt())
            val entries = ArrayList<ArchivePageEntry>(directory.entryCount.coerceAtMost(10_000).toInt())
            var cursor = 0
            while (cursor < bytes.size && entries.size.toLong() < directory.entryCount) {
                if (bytes.u32(cursor) != CENTRAL_HEADER_SIGNATURE) throw IOException("Invalid ZIP central directory")
                val flags = bytes.u16(cursor + 8)
                val method = bytes.u16(cursor + 10)
                val dosTime = bytes.u16(cursor + 12)
                val dosDate = bytes.u16(cursor + 14)
                var compressedSize = bytes.u32(cursor + 20)
                var size = bytes.u32(cursor + 24)
                val nameLength = bytes.u16(cursor + 28)
                val extraLength = bytes.u16(cursor + 30)
                val commentLength = bytes.u16(cursor + 32)
                var localHeaderOffset = bytes.u32(cursor + 42)
                val end = cursor + 46 + nameLength + extraLength + commentLength
                if (end > bytes.size) throw EOFException("Truncated ZIP central directory")

                val nameBytes = bytes.copyOfRange(cursor + 46, cursor + 46 + nameLength)
                val name = nameBytes.toString(if (flags and UTF8_FLAG != 0) Charsets.UTF_8 else cp437)
                    .replace('\\', '/')
                val extraStart = cursor + 46 + nameLength
                val zip64 = parseZip64Extra(
                    bytes = bytes,
                    start = extraStart,
                    length = extraLength,
                    needsSize = size == UINT32_MAX,
                    needsCompressedSize = compressedSize == UINT32_MAX,
                    needsOffset = localHeaderOffset == UINT32_MAX,
                )
                if (size == UINT32_MAX) size = zip64.size ?: throw IOException("Missing ZIP64 entry size")
                if (compressedSize == UINT32_MAX) {
                    compressedSize = zip64.compressedSize ?: throw IOException("Missing ZIP64 compressed size")
                }
                if (localHeaderOffset == UINT32_MAX) {
                    localHeaderOffset = zip64.localHeaderOffset ?: throw IOException("Missing ZIP64 local header offset")
                }

                entries += ArchivePageEntry(
                    name = name,
                    size = size,
                    compressedSize = compressedSize,
                    localHeaderOffset = localHeaderOffset,
                    compressionMethod = method,
                    flags = flags,
                    lastModifiedMillis = dosTimestamp(dosDate, dosTime),
                    isDirectory = name.endsWith('/'),
                )
                cursor = end
            }
            if (entries.size.toLong() != directory.entryCount) throw IOException("ZIP entry count mismatch")
            return entries
        } catch (e: SmbLibraryException) {
            throw e
        } catch (e: Throwable) {
            throw SmbLibraryException.ZipBroken(archivePath, e)
        }
    }

    private fun locateDirectory(data: RandomAccessData): DirectoryLocation {
        if (data.size < 22L) throw IOException("ZIP is too small")
        val tailLength = minOf(data.size, MAX_EOCD_SIZE.toLong()).toInt()
        val tailOffset = data.size - tailLength
        val tail = data.readFully(tailOffset, tailLength)
        val eocdIndex = (tail.size - 22 downTo 0).firstOrNull {
            tail.u32(it) == EOCD_SIGNATURE && it + 22 + tail.u16(it + 20) == tail.size
        }
            ?: throw IOException("ZIP end record not found")
        val eocdOffset = tailOffset + eocdIndex

        val diskNumber = tail.u16(eocdIndex + 4)
        val directoryDisk = tail.u16(eocdIndex + 6)
        val diskEntryCount = tail.u16(eocdIndex + 8)
        if (diskNumber != 0 || directoryDisk != 0) throw IOException("Multi-disk ZIP is not supported")

        val entryCount = tail.u16(eocdIndex + 10).toLong()
        if (diskEntryCount.toLong() != entryCount && entryCount != UINT16_MAX.toLong()) {
            throw IOException("Multi-disk ZIP is not supported")
        }
        val size = tail.u32(eocdIndex + 12)
        val offset = tail.u32(eocdIndex + 16)
        if (entryCount != UINT16_MAX.toLong() && size != UINT32_MAX && offset != UINT32_MAX) {
            return DirectoryLocation(offset, size, entryCount).validated(data.size)
        }

        if (eocdOffset < 20L) throw IOException("ZIP64 locator is missing")
        val locator = data.readFully(eocdOffset - 20L, 20)
        if (locator.u32(0) != ZIP64_LOCATOR_SIGNATURE) throw IOException("ZIP64 locator is invalid")
        if (locator.u32(4) != 0L || locator.u32(16) != 1L) throw IOException("Multi-disk ZIP64 is not supported")
        val zip64Offset = locator.i64(8)
        val zip64 = data.readFully(zip64Offset, 56)
        if (zip64.u32(0) != ZIP64_EOCD_SIGNATURE) throw IOException("ZIP64 end record is invalid")
        return DirectoryLocation(
            offset = zip64.i64(48),
            size = zip64.i64(40),
            entryCount = zip64.i64(32),
        ).validated(data.size)
    }

    private fun parseZip64Extra(
        bytes: ByteArray,
        start: Int,
        length: Int,
        needsSize: Boolean,
        needsCompressedSize: Boolean,
        needsOffset: Boolean,
    ): Zip64Values {
        var cursor = start
        val end = start + length
        while (cursor + 4 <= end) {
            val headerId = bytes.u16(cursor)
            val dataSize = bytes.u16(cursor + 2)
            val valueEnd = cursor + 4 + dataSize
            if (valueEnd > end) throw EOFException("Truncated ZIP extra field")
            if (headerId == ZIP64_EXTRA_ID) {
                var valueCursor = cursor + 4
                fun nextLong(): Long {
                    if (valueCursor + 8 > valueEnd) throw EOFException("Truncated ZIP64 extra field")
                    return bytes.i64(valueCursor).also { valueCursor += 8 }
                }
                return Zip64Values(
                    size = if (needsSize) nextLong() else null,
                    compressedSize = if (needsCompressedSize) nextLong() else null,
                    localHeaderOffset = if (needsOffset) nextLong() else null,
                )
            }
            cursor = valueEnd
        }
        return Zip64Values(null, null, null)
    }

    private fun DirectoryLocation.validated(fileSize: Long): DirectoryLocation {
        if (offset < 0L || size < 0L || entryCount < 0L || offset > fileSize || size > fileSize - offset) {
            throw IOException("ZIP central directory is outside the archive")
        }
        return this
    }

    private fun dosTimestamp(date: Int, time: Int): Long = try {
        LocalDateTime.of(
            1980 + ((date ushr 9) and 0x7F),
            (date ushr 5) and 0x0F,
            date and 0x1F,
            (time ushr 11) and 0x1F,
            (time ushr 5) and 0x3F,
            (time and 0x1F) * 2,
        ).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: DateTimeException) {
        0L
    }

    private data class DirectoryLocation(val offset: Long, val size: Long, val entryCount: Long)
    private data class Zip64Values(val size: Long?, val compressedSize: Long?, val localHeaderOffset: Long?)

    private const val UTF8_FLAG = 1 shl 11
    private const val ZIP64_EXTRA_ID = 0x0001
    private const val UINT16_MAX = 0xFFFF
    private const val UINT32_MAX = 0xFFFF_FFFFL
}

internal object ZipEntryStream {
    private const val LOCAL_HEADER_SIGNATURE = 0x04034B50L
    private const val ENCRYPTED_FLAG = 1
    private const val STORED = 0
    private const val DEFLATED = 8

    fun open(data: RandomAccessData, entry: ArchivePageEntry, archivePath: String): InputStream {
        if (entry.flags and ENCRYPTED_FLAG != 0) {
            throw SmbLibraryException.ZipEncrypted(archivePath, IOException("Encrypted ZIP entry: ${entry.name}"))
        }
        if (entry.localHeaderOffset < 0L || entry.localHeaderOffset > data.size - 30L) {
            throw SmbLibraryException.ZipBroken(archivePath, IOException("Invalid local ZIP header offset"))
        }
        val header = data.readFully(entry.localHeaderOffset, 30)
        if (header.u32(0) != LOCAL_HEADER_SIGNATURE) {
            throw SmbLibraryException.ZipBroken(archivePath, IOException("Invalid local ZIP header"))
        }
        val nameLength = header.u16(26)
        val extraLength = header.u16(28)
        val dataOffset = entry.localHeaderOffset + 30L + nameLength + extraLength
        if (
            entry.compressedSize < 0L ||
            dataOffset < 0L ||
            dataOffset > data.size ||
            entry.compressedSize > data.size - dataOffset
        ) {
            throw SmbLibraryException.ZipBroken(archivePath, IOException("ZIP entry data is outside the archive"))
        }

        val compressed = RandomAccessRangeInputStream(data, dataOffset, entry.compressedSize)
        return when (entry.compressionMethod) {
            STORED -> compressed
            DEFLATED -> InflaterInputStream(compressed, Inflater(true), 64 * 1024)
            else -> throw SmbLibraryException.ZipBroken(
                archivePath,
                IOException("Unsupported ZIP compression method: ${entry.compressionMethod}"),
            )
        }
    }
}

private class RandomAccessRangeInputStream(
    private val data: RandomAccessData,
    private val start: Long,
    private val length: Long,
) : InputStream() {
    private var position = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset + length > target.size) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (position >= this.length) return -1
        val requested = minOf(length.toLong(), this.length - position).toInt()
        val read = data.read(start + position, target, offset, requested)
        if (read <= 0) throw EOFException("Unexpected end of remote ZIP entry")
        position += read
        return read
    }

    override fun available(): Int = minOf(this.length - position, Int.MAX_VALUE.toLong()).toInt()
}

private fun RandomAccessData.readFully(offset: Long, length: Int): ByteArray {
    if (offset < 0L || length < 0 || offset > size || length.toLong() > size - offset) {
        throw EOFException("Read is outside the remote file")
    }
    val result = ByteArray(length)
    var total = 0
    while (total < length) {
        val read = read(offset + total, result, total, length - total)
        if (read <= 0) throw EOFException("Unexpected end of remote file")
        total += read
    }
    return result
}

private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32(offset: Int): Long = (u16(offset).toLong() and 0xFFFFL) or ((u16(offset + 2).toLong() and 0xFFFFL) shl 16)

private fun ByteArray.i64(offset: Int): Long = u32(offset) or (u32(offset + 4) shl 32)

data class ArchivePageEntry(
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val localHeaderOffset: Long,
    val compressionMethod: Int,
    val flags: Int,
    val lastModifiedMillis: Long,
    val isDirectory: Boolean,
)

class ZipEntryHandle(
    val inputStream: InputStream,
    private val closeable: Closeable,
) : Closeable {
    override fun close() {
        try {
            inputStream.close()
        } finally {
            closeable.close()
        }
    }
}
