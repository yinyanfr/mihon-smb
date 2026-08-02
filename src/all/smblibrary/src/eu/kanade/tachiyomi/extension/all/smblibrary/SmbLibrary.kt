package eu.kanade.tachiyomi.extension.all.smblibrary

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.R
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Source
abstract class SmbLibrary :
    KeiSource(),
    ConfigurableSource,
    UnmeteredSource {
    override val supportsLatest = false

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(::interceptSmbImage)

    private val preferences: SharedPreferences by getPreferencesLazy()
    private val repository = SmbRepository()
    private val archiveCache = ArchiveCache(repository)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val defaultCoverBytes by lazy {
        applicationContext.resources.openRawResource(R.drawable.default_manga_cover).use { it.readBytes() }
    }
    private val placeholderHtmlBytes by lazy { createPlaceholderHtml().toByteArray(Charsets.UTF_8) }

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        MangasPage(listMangaFolders(MangaSort.DEFAULT), hasNextPage = false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        val sort = filters.filterIsInstance<MangaSortFilter>().firstOrNull()?.selected ?: MangaSort.DEFAULT
        val mangas = listMangaFolders(sort)
            .filter { normalizedQuery.isEmpty() || it.title.contains(normalizedQuery, ignoreCase = true) }
        MangasPage(mangas, hasNextPage = false)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(MangaSortFilter())

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val updatedManga = if (fetchDetails) {
            val path = PathCodec.mangaPath(manga.url)
            manga.apply {
                title = path.substringAfterLast('/')
                description = "SMB relative path: $path"
                status = SManga.UNKNOWN
                thumbnail_url = DEFAULT_COVER_URL
            }
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) chapterList(updatedManga) else chapters
        SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        pageList(chapter)
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(textPreference(screen, PREF_HOST, "Host", "NAS hostname or IP address"))
        screen.addPreference(textPreference(screen, PREF_PORT, "Port", "445", number = true))
        screen.addPreference(textPreference(screen, PREF_SHARE, "Share", "SMB share name"))
        screen.addPreference(textPreference(screen, PREF_ROOT, "Root path", "Path inside the share, can be empty"))
        screen.addPreference(textPreference(screen, PREF_USERNAME, "Username", "Can be empty for anonymous shares"))
        screen.addPreference(textPreference(screen, PREF_PASSWORD, "Password", "Not shown", password = true))
        screen.addPreference(textPreference(screen, PREF_DOMAIN, "Domain", "Optional domain or workgroup"))
        screen.addPreference(textPreference(screen, PREF_TIMEOUT, "Connection timeout (ms)", "10000", number = true))
        screen.addPreference(
            SwitchPreferenceCompat(screen.context).apply {
                title = "Test connection"
                summary = "Checks Host, Port, Share and Root path"
                setOnPreferenceClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        val message = try {
                            repository.testConnection(currentConfig())
                            "SMB connection succeeded."
                        } catch (e: Throwable) {
                            e.userMessage()
                        }
                        mainHandler.post {
                            Toast.makeText(screen.context, message, Toast.LENGTH_LONG).show()
                            isChecked = false
                        }
                    }
                    true
                }
            },
        )
    }

    private fun interceptSmbImage(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != LOCAL_HOST) {
            return chain.proceed(request)
        }

        return when (request.url.encodedPath) {
            "/cover" -> PageResponseFactory.fromBytes(
                url = request.url.toString(),
                mimeType = "image/png",
                bytes = defaultCoverBytes,
            )
            "/page" -> {
                val descriptor = PathCodec.page(request.url.toString())
                val config = currentConfig()
                when (descriptor.type) {
                    PageType.RemoteImage -> {
                        val handle = repository.openFile(config, descriptor.pagePath)
                        PageResponseFactory.fromRemoteFile(request.url.toString(), descriptor, handle)
                    }
                    PageType.ArchiveEntry -> {
                        val fingerprint = archiveFingerprint(config, descriptor)
                        val file = archiveCache.getOrDownload(config, fingerprint)
                        val handle = archiveCache.openEntry(file, descriptor.pagePath, descriptor.chapterPath)
                        PageResponseFactory.fromZipEntry(request.url.toString(), descriptor, handle)
                    }
                }
            }
            else -> PageResponseFactory.fromBytes(
                url = request.url.toString(),
                mimeType = "text/html; charset=utf-8",
                bytes = placeholderHtmlBytes,
            )
        }
    }

    private fun listMangaFolders(sort: MangaSort): List<SManga> {
        val config = currentConfig()
        return MangaListingSorter.sorted(
            repository.list(config, "")
                .filter { it.isDirectory },
            sort,
        ).map { entry ->
            SManga.create().apply {
                title = entry.name
                url = PathCodec.mangaUrl(entry.relativePath)
                status = SManga.UNKNOWN
                initialized = true
                thumbnail_url = DEFAULT_COVER_URL
            }
        }
    }

    private fun chapterList(manga: SManga): List<SChapter> {
        val config = currentConfig()
        val mangaPath = PathCodec.mangaPath(manga.url)
        val chapters = repository.browse(config) {
            val entries = list(mangaPath)
            buildList {
                val rootImages = entries.filter { !it.isDirectory && ContentDetector.isSupportedImage(it.name) }
                if (rootImages.isNotEmpty()) {
                    add(
                        ChapterDescriptor(
                            mangaPath = mangaPath,
                            type = ChapterType.RootImages,
                            chapterPath = "",
                            name = "本卷",
                            size = rootImages.sumOf { it.size.coerceAtLeast(0L) },
                            lastModifiedMillis = rootImages.maxOf { it.lastModifiedMillis },
                        ),
                    )
                }

                entries.filter { it.isDirectory }.forEach { folder ->
                    val childEntries = list(folder.relativePath)
                    if (childEntries.any { !it.isDirectory && ContentDetector.isSupportedImage(it.name) }) {
                        add(
                            ChapterDescriptor(
                                mangaPath = mangaPath,
                                type = ChapterType.ImageDirectory,
                                chapterPath = folder.relativePath,
                                name = folder.name,
                                size = folder.size,
                                lastModifiedMillis = folder.lastModifiedMillis,
                            ),
                        )
                    }
                }

                entries.filter { !it.isDirectory && ContentDetector.isArchive(it.name) }.forEach { archive ->
                    add(
                        ChapterDescriptor(
                            mangaPath = mangaPath,
                            type = ChapterType.Archive,
                            chapterPath = archive.relativePath,
                            name = archive.name,
                            size = archive.size,
                            lastModifiedMillis = archive.lastModifiedMillis,
                        ),
                    )
                }
            }
        }

        return NaturalSort.sortedBy(chapters) { it.name }.asReversed().map { descriptor ->
            SChapter.create().apply {
                name = descriptor.name
                url = PathCodec.chapterUrl(descriptor)
                date_upload = descriptor.lastModifiedMillis
            }
        }
    }

    private fun pageList(chapter: SChapter): List<Page> {
        val config = currentConfig()
        val descriptor = PathCodec.chapter(chapter.url)
        return when (descriptor.type) {
            ChapterType.RootImages -> imagePages(config, descriptor, descriptor.mangaPath)
            ChapterType.ImageDirectory -> imagePages(config, descriptor, descriptor.chapterPath)
            ChapterType.Archive -> archivePages(config, descriptor)
        }
    }

    private fun imagePages(config: SmbConfig, chapter: ChapterDescriptor, folderPath: String): List<Page> = NaturalSort.sortedBy(
        repository.list(config, folderPath)
            .filter { !it.isDirectory && ContentDetector.isSupportedImage(it.name) },
    ) { it.name }.mapIndexed { index, entry ->
        val descriptor = PageDescriptor(
            type = PageType.RemoteImage,
            mangaPath = chapter.mangaPath,
            chapterPath = chapter.chapterPath,
            pagePath = entry.relativePath,
            index = index,
            size = entry.size,
            lastModifiedMillis = entry.lastModifiedMillis,
        )
        Page(index, imageUrl = PathCodec.pageUrl(descriptor))
    }

    private fun archivePages(config: SmbConfig, chapter: ChapterDescriptor): List<Page> {
        val archiveEntry = repository.metadata(config, chapter.chapterPath)
        val fingerprint = ArchiveFingerprint(
            cacheNamespace = config.cacheNamespace,
            relativePath = archiveEntry.relativePath,
            size = archiveEntry.size,
            lastModifiedMillis = archiveEntry.lastModifiedMillis,
        )
        val archiveFile = archiveCache.getOrDownload(config, fingerprint)
        return archiveCache.listImageEntries(archiveFile, chapter.chapterPath).mapIndexed { index, entry ->
            val descriptor = PageDescriptor(
                type = PageType.ArchiveEntry,
                mangaPath = chapter.mangaPath,
                chapterPath = chapter.chapterPath,
                pagePath = entry.name,
                index = index,
                size = entry.size,
                lastModifiedMillis = entry.lastModifiedMillis,
                archiveSize = archiveEntry.size,
                archiveLastModifiedMillis = archiveEntry.lastModifiedMillis,
            )
            Page(index, imageUrl = PathCodec.pageUrl(descriptor))
        }
    }

    private fun currentConfig(): SmbConfig = SmbConfig(
        host = preferences.getString(PREF_HOST, "").orEmpty().trim().removePrefix("smb://").trim('/'),
        port = preferences.getString(PREF_PORT, "445").orEmpty().toIntOrNull() ?: 445,
        share = preferences.getString(PREF_SHARE, "").orEmpty().trim().trim('/'),
        rootPath = PathCodec.normalizeRoot(preferences.getString(PREF_ROOT, "").orEmpty()),
        username = preferences.getString(PREF_USERNAME, "").orEmpty(),
        password = preferences.getString(PREF_PASSWORD, "").orEmpty(),
        domain = preferences.getString(PREF_DOMAIN, "").orEmpty(),
        timeoutMillis = preferences.getString(PREF_TIMEOUT, "10000").orEmpty().toLongOrNull()
            ?.coerceIn(1000L, 120000L) ?: 10000L,
    )

    private fun textPreference(
        screen: PreferenceScreen,
        key: String,
        title: String,
        summary: String,
        password: Boolean = false,
        number: Boolean = false,
    ): EditTextPreference = EditTextPreference(screen.context).apply {
        this.key = key
        this.title = title
        this.summary = if (password && preferences.getString(key, "").orEmpty().isNotEmpty()) {
            "Configured"
        } else {
            summary
        }
        dialogTitle = title
        setDefaultValue(
            if (key == PREF_PORT) {
                "445"
            } else if (key == PREF_TIMEOUT) {
                "10000"
            } else {
                ""
            },
        )
        if (password || number) {
            setOnBindEditTextListener {
                it.inputType = when {
                    password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    number -> InputType.TYPE_CLASS_NUMBER
                    else -> InputType.TYPE_CLASS_TEXT
                }
            }
        }
        setOnPreferenceChangeListener { preference, newValue ->
            preference.summary = if (password && (newValue as String).isNotEmpty()) "Configured" else summary
            true
        }
    }

    private fun archiveFingerprint(config: SmbConfig, descriptor: PageDescriptor): ArchiveFingerprint {
        if (descriptor.archiveSize > 0L && descriptor.archiveLastModifiedMillis > 0L) {
            return ArchiveFingerprint(
                cacheNamespace = config.cacheNamespace,
                relativePath = descriptor.chapterPath,
                size = descriptor.archiveSize,
                lastModifiedMillis = descriptor.archiveLastModifiedMillis,
            )
        }

        val archiveEntry = repository.metadata(config, descriptor.chapterPath)
        return ArchiveFingerprint(
            cacheNamespace = config.cacheNamespace,
            relativePath = archiveEntry.relativePath,
            size = archiveEntry.size,
            lastModifiedMillis = archiveEntry.lastModifiedMillis,
        )
    }

    private fun createPlaceholderHtml(): String = """
        <!doctype html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>SMB Library</title>
            <style>
                body {
                    margin: 0;
                    min-height: 100vh;
                    font-family: sans-serif;
                    background: #24524a;
                    color: #f4f1e8;
                    display: grid;
                    place-items: center;
                }
                main {
                    width: min(34rem, calc(100vw - 3rem));
                }
                h1 {
                    font-size: 2rem;
                    margin: 0 0 1rem;
                }
                p, li {
                    line-height: 1.55;
                }
                code {
                    background: rgba(255, 255, 255, 0.14);
                    border-radius: 4px;
                    padding: 0.1rem 0.3rem;
                }
            </style>
        </head>
        <body>
            <main>
                <h1>SMB Library</h1>
                <p>This source reads manga directly from an SMB2/SMB3 share. It does not provide a public website.</p>
                <p>Open the extension settings and configure:</p>
                <ul>
                    <li>Host and port</li>
                    <li>Share name</li>
                    <li>Root path inside the share</li>
                    <li>Username, password and optional domain</li>
                </ul>
                <p>Example: for <code>//nas/shared/dl</code>, use share <code>shared</code> and root path <code>dl</code>.</p>
            </main>
        </body>
        </html>
    """.trimIndent()

    private fun Throwable.userMessage(): String = when (this) {
        is SmbLibraryException -> message ?: "SMB Library error"
        else -> cause?.userMessage() ?: message ?: "SMB Library error"
    }

    companion object {
        private const val LOCAL_HOST = "smb.library.local"
        private const val DEFAULT_COVER_URL = "https://$LOCAL_HOST/cover"
        private const val PREF_HOST = "smb_host"
        private const val PREF_PORT = "smb_port"
        private const val PREF_SHARE = "smb_share"
        private const val PREF_ROOT = "smb_root"
        private const val PREF_USERNAME = "smb_username"
        private const val PREF_PASSWORD = "smb_password"
        private const val PREF_DOMAIN = "smb_domain"
        private const val PREF_TIMEOUT = "smb_timeout"
    }
}
