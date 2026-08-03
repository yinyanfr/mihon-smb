package eu.kanade.tachiyomi.extension.all.smblibrary

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbRepository {
    fun testConnection(config: eu.kanade.tachiyomi.extension.all.smblibrary.SmbConfig) {
        requireConfigured(config)
        withShare(config) { share ->
            val path = remotePath(config, "")
            try {
                share.list(path).take(1)
            } catch (e: Throwable) {
                throw translate(path, e)
            }
        }
    }

    fun list(config: eu.kanade.tachiyomi.extension.all.smblibrary.SmbConfig, relativePath: String): List<RemoteEntry> = browse(config) { list(relativePath) }

    fun <T> browse(
        config: eu.kanade.tachiyomi.extension.all.smblibrary.SmbConfig,
        block: DirectoryBrowser.() -> T,
    ): T {
        requireConfigured(config)
        return withShare(config) { share ->
            DirectoryBrowser { relativePath -> listOnShare(config, share, relativePath) }.block()
        }
    }

    fun metadata(config: eu.kanade.tachiyomi.extension.all.smblibrary.SmbConfig, relativePath: String): RemoteEntry {
        requireConfigured(config)
        val safeRelativePath = PathCodec.normalizeRelativePath(relativePath)
        return list(config, safeRelativePath.substringBeforeLast('/', ""))
            .firstOrNull { it.relativePath == safeRelativePath }
            ?: throw SmbLibraryException.FileRemoved(safeRelativePath)
    }

    fun openFile(config: eu.kanade.tachiyomi.extension.all.smblibrary.SmbConfig, relativePath: String): RemoteFileHandle {
        requireConfigured(config)
        val safeRelativePath = PathCodec.normalizeRelativePath(relativePath)
        val client = newClient(config)
        var connection: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        var file: File? = null
        try {
            connection = client.connect(config.host, config.port)
            session = connection.authenticate(auth(config))
            share = session.connectShare(config.share) as DiskShare
            val path = remotePath(config, safeRelativePath)
            file = share.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            return RemoteFileHandle(
                relativePath = safeRelativePath,
                inputStream = file.inputStream,
                closeables = listOf(file, share, session, connection, client),
            )
        } catch (e: Throwable) {
            closeQuietly(file)
            closeQuietly(share)
            closeQuietly(session)
            closeQuietly(connection)
            closeQuietly(client)
            throw translate(safeRelativePath, e)
        }
    }

    fun openRandomAccessFile(
        config: eu.kanade.tachiyomi.extension.all.smblibrary.SmbConfig,
        relativePath: String,
    ): RemoteRandomAccessHandle {
        requireConfigured(config)
        val safeRelativePath = PathCodec.normalizeRelativePath(relativePath)
        val client = newClient(config)
        var connection: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        var file: File? = null
        try {
            connection = client.connect(config.host, config.port)
            session = connection.authenticate(auth(config))
            share = session.connectShare(config.share) as DiskShare
            val path = remotePath(config, safeRelativePath)
            file = share.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            val information = file.fileInformation
            return RemoteRandomAccessHandle(
                relativePath = safeRelativePath,
                size = information.standardInformation.endOfFile,
                lastModifiedMillis = information.basicInformation.lastWriteTime.toEpochMillis(),
                file = file,
                closeables = listOf(file, share, session, connection, client),
            ).also {
                file = null
            }
        } catch (e: Throwable) {
            closeQuietly(file)
            closeQuietly(share)
            closeQuietly(session)
            closeQuietly(connection)
            closeQuietly(client)
            throw translate(safeRelativePath, e)
        }
    }

    private fun <T> withShare(
        config: eu.kanade.tachiyomi.extension.all.smblibrary.SmbConfig,
        block: (DiskShare) -> T,
    ): T {
        val client = newClient(config)
        try {
            client.connect(config.host, config.port).use { connection ->
                val session = connection.authenticate(auth(config))
                session.use {
                    val share = session.connectShare(config.share) as DiskShare
                    share.use {
                        return block(share)
                    }
                }
            }
        } catch (e: Throwable) {
            throw translate(config.rootPath.ifEmpty { "/" }, e)
        } finally {
            closeQuietly(client)
        }
    }

    private fun newClient(config: eu.kanade.tachiyomi.extension.all.smblibrary.SmbConfig): SMBClient {
        val smbjConfig = SmbConfig.builder()
            .withTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
            .withSoTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        return SMBClient(smbjConfig)
    }

    private fun auth(config: eu.kanade.tachiyomi.extension.all.smblibrary.SmbConfig): AuthenticationContext = AuthenticationContext(
        config.username,
        config.password.toCharArray(),
        config.domain.ifBlank { null },
    )

    private fun listOnShare(
        config: eu.kanade.tachiyomi.extension.all.smblibrary.SmbConfig,
        share: DiskShare,
        relativePath: String,
    ): List<RemoteEntry> {
        val safeRelativePath = PathCodec.normalizeRelativePath(relativePath, allowEmpty = true)
        val path = remotePath(config, safeRelativePath)
        try {
            return share.list(path)
                .asSequence()
                .filterNot { it.fileName == "." || it.fileName == ".." }
                .map { info ->
                    val childRelative = if (safeRelativePath.isEmpty()) {
                        PathCodec.normalizeRelativePath(info.fileName)
                    } else {
                        PathCodec.join(safeRelativePath, info.fileName)
                    }
                    RemoteEntry(
                        relativePath = childRelative,
                        name = info.fileName,
                        isDirectory = isDirectory(info.fileAttributes),
                        size = info.endOfFile,
                        lastModifiedMillis = info.lastWriteTime.toEpochMillis(),
                    )
                }
                .toList()
        } catch (e: Throwable) {
            throw translate(path, e)
        }
    }

    private fun requireConfigured(config: eu.kanade.tachiyomi.extension.all.smblibrary.SmbConfig) {
        if (!config.isUsable) throw SmbLibraryException.NotConfigured()
    }

    private fun remotePath(config: eu.kanade.tachiyomi.extension.all.smblibrary.SmbConfig, relativePath: String): String {
        val root = PathCodec.normalizeRoot(config.rootPath)
        val relative = PathCodec.normalizeRelativePath(relativePath, allowEmpty = true)
        return when {
            root.isEmpty() -> relative
            relative.isEmpty() -> root
            else -> PathCodec.join(root, relative)
        }
    }

    private fun isDirectory(attributes: Long): Boolean = attributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L

    internal fun translate(path: String, throwable: Throwable): SmbLibraryException {
        if (throwable is SmbLibraryException) return throwable
        throwable.findCause<UnknownHostException>()?.let { return SmbLibraryException.HostUnreachable(it) }
        throwable.findCause<SocketTimeoutException>()?.let { return SmbLibraryException.Timeout(it) }
        throwable.findCause<ConnectException>()?.let { return SmbLibraryException.TcpConnectionFailed(it) }
        throwable.findCause<NoRouteToHostException>()?.let { return SmbLibraryException.TcpConnectionFailed(it) }
        throwable.findCause<SMBApiException>()?.let { return translateStatus(path, it) }
        return when (throwable) {
            is TransportException -> SmbLibraryException.TcpConnectionFailed(throwable)
            is IOException -> SmbLibraryException.ReadDisconnected(path, throwable)
            else -> SmbLibraryException.HostUnreachable(throwable)
        }
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? = generateSequence(this) { it.cause }
        .filterIsInstance<T>()
        .firstOrNull()

    private fun translateStatus(path: String, exception: SMBApiException): SmbLibraryException = when (exception.status.name) {
        "STATUS_LOGON_FAILURE",
        "STATUS_ACCOUNT_DISABLED",
        "STATUS_ACCOUNT_RESTRICTION",
        -> SmbLibraryException.AuthenticationFailed(exception)
        "STATUS_ACCESS_DENIED" -> SmbLibraryException.AccessDenied(path, exception)
        "STATUS_BAD_NETWORK_NAME" -> SmbLibraryException.ShareMissing(exception)
        "STATUS_OBJECT_NAME_NOT_FOUND",
        "STATUS_OBJECT_PATH_NOT_FOUND",
        "STATUS_NO_SUCH_FILE",
        -> SmbLibraryException.PathMissing(path, exception)
        "STATUS_SHARING_VIOLATION" -> SmbLibraryException.AccessDenied(path, exception)
        else -> SmbLibraryException.AccessDenied(path, exception)
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Throwable) {
        }
    }
}

class DirectoryBrowser internal constructor(
    private val lister: (String) -> List<RemoteEntry>,
) {
    fun list(relativePath: String): List<RemoteEntry> = lister(relativePath)
}

class RemoteFileHandle(
    val relativePath: String,
    val inputStream: InputStream,
    private val closeables: List<AutoCloseable?>,
) : Closeable {
    override fun close() {
        closeables.forEach {
            try {
                it?.close()
            } catch (_: Throwable) {
            }
        }
    }
}

class RemoteRandomAccessHandle(
    val relativePath: String,
    override val size: Long,
    val lastModifiedMillis: Long,
    private val file: File,
    private val closeables: List<AutoCloseable?>,
) : RandomAccessData {
    @Synchronized
    override fun read(offset: Long, target: ByteArray, targetOffset: Int, length: Int): Int {
        if (offset < 0L || targetOffset < 0 || length < 0 || length > target.size - targetOffset) {
            throw IndexOutOfBoundsException()
        }
        if (offset >= size || length == 0) return -1
        return file.read(target, offset, targetOffset, minOf(length, MAX_READ_SIZE))
    }

    override fun close() {
        closeables.forEach {
            try {
                it?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        const val MAX_READ_SIZE = 1024 * 1024
    }
}
