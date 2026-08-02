package eu.kanade.tachiyomi.extension.all.smblibrary

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream

object PageResponseFactory {
    fun fromRemoteFile(url: String, descriptor: PageDescriptor, handle: RemoteFileHandle): Response = response(
        url = url,
        mimeType = ContentDetector.mimeType(descriptor.pagePath),
        length = descriptor.size,
        inputStream = handle.inputStream,
        closeable = handle,
    )

    fun fromZipEntry(url: String, descriptor: PageDescriptor, handle: ZipEntryHandle): Response = response(
        url = url,
        mimeType = ContentDetector.mimeType(descriptor.pagePath),
        length = descriptor.size,
        inputStream = handle.inputStream,
        closeable = handle,
    )

    fun fromCover(url: String, handle: CoverHandle): Response = response(
        url = url,
        mimeType = ContentDetector.mimeType(handle.path),
        length = handle.length,
        inputStream = handle.inputStream,
        closeable = handle,
    )

    fun fromBytes(url: String, mimeType: String, bytes: ByteArray): Response = response(
        url = url,
        mimeType = mimeType,
        length = bytes.size.toLong(),
        inputStream = ByteArrayInputStream(bytes),
        closeable = Closeable { },
    )

    private fun response(
        url: String,
        mimeType: String,
        length: Long,
        inputStream: InputStream,
        closeable: Closeable,
    ): Response = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(StreamResponseBody(mimeType.toMediaTypeOrNull(), length, inputStream, closeable))
        .build()
}

private class StreamResponseBody(
    private val mediaType: MediaType?,
    private val length: Long,
    inputStream: InputStream,
    closeable: Closeable,
) : ResponseBody() {
    private val source: BufferedSource = ClosingInputStream(inputStream, closeable).source().buffer()

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = length

    override fun source(): BufferedSource = source
}

private class ClosingInputStream(
    inputStream: InputStream,
    private val closeable: Closeable,
) : FilterInputStream(inputStream) {
    override fun close() {
        try {
            super.close()
        } finally {
            closeable.close()
        }
    }
}
