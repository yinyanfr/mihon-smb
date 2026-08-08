package eu.kanade.tachiyomi.extension.all.smblibrary

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import eu.kanade.tachiyomi.extension.R
import java.io.FileNotFoundException

class CoverProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = if (uri.isCoverUri()) MIME_TYPE else null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        requireCoverUri(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> FILE_NAME
                        OpenableColumns.SIZE -> null
                        else -> null
                    }
                }.toTypedArray(),
            )
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireCoverUri(uri)
        if (mode != "r") throw FileNotFoundException("SMB Library cover is read-only")
        return openPipeHelper(uri, MIME_TYPE, null, Unit) { output, _, _, _, _ ->
            val resources = checkNotNull(context).resources
            resources.openRawResource(R.drawable.default_manga_cover).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                    input.copyTo(stream)
                }
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider")

    private fun requireCoverUri(uri: Uri) {
        if (!uri.isCoverUri()) throw FileNotFoundException("Unknown SMB Library cover URI")
    }

    private fun Uri.isCoverUri(): Boolean = authority == AUTHORITY && path == COVER_PATH

    companion object {
        const val COVER_URI = "content://eu.kanade.tachiyomi.extension.all.smblibrary.cover/placeholder-v2.png"

        private const val AUTHORITY = "eu.kanade.tachiyomi.extension.all.smblibrary.cover"
        private const val COVER_PATH = "/placeholder-v2.png"
        private const val FILE_NAME = "smb-library-placeholder.png"
        private const val MIME_TYPE = "image/png"
    }
}
