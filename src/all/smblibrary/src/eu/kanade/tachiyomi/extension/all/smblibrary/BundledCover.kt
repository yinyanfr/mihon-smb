package eu.kanade.tachiyomi.extension.all.smblibrary

import eu.kanade.tachiyomi.extension.R
import keiyoushi.utils.applicationContext

object BundledCover {
    const val HOST = "smb.library.local"
    const val PATH = "/cover/placeholder-v3.png"
    const val URL = "https://$HOST$PATH"

    val bytes: ByteArray by lazy {
        applicationContext.packageManager
            .getResourcesForApplication(EXTENSION_PACKAGE)
            .openRawResource(R.drawable.default_manga_cover)
            .use { it.readBytes() }
    }

    private const val EXTENSION_PACKAGE = "eu.kanade.tachiyomi.extension.all.smblibrary"
}
