package net.guteam.gupet

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.zip.ZipInputStream

/**
 * Магазин скинов GUPet для Android.
 *
 * Каталог строится по GitHub Releases репозитория GreatUserTeam/petckins.
 * Для каждой карточки скачивается .gupet, из него извлекаются превью idle.png
 * и id скина. Сеть работает в фоновых потоках, результат возвращается в WebView
 * через window.onRemoteSkinsLoaded / window.onRemoteSkinDownloaded.
 */
internal object SkinMarket {
    private const val RELEASES_URL =
        "https://api.github.com/repos/GreatUserTeam/petckins/releases?per_page=100"
    private const val USER_AGENT = "GUPet-Android/2.0.0 (https://github.com/GreatUserTeam/GUPet)"
    private const val MAX_API_BYTES = 4 * 1024 * 1024
    private const val MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
    private const val MAX_PREVIEW_BYTES = 512 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 12
    private const val PREVIEW_WORKERS = 8

    private val idPattern = Regex("[a-z0-9][a-z0-9_-]{0,39}")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fetchExecutor = Executors.newSingleThreadExecutor(daemonFactory("gupet-market"))
    private val previewExecutor =
        Executors.newFixedThreadPool(PREVIEW_WORKERS, daemonFactory("gupet-preview"))
    private val downloadExecutor = Executors.newSingleThreadExecutor(daemonFactory("gupet-download"))

    internal data class RemoteSkin(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val downloadUrl: String,
        val version: String,
        val preview: String,
        val releaseUrl: String,
        val localId: String,
        val installed: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("author", author)
            .put("description", description)
            .put("download_url", downloadUrl)
            .put("version", version)
            .put("preview", preview)
            .put("release_url", releaseUrl)
            .put("local_id", localId)
            .put("installed", installed)
    }

    internal data class DownloadResult(
        val id: String,
        val name: String,
        val author: String,
        val downloadUrl: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("author", author)
            .put("download_url", downloadUrl)
    }

    internal interface FetchCallback {
        fun onSuccess(items: List<RemoteSkin>)
        fun onError(message: String)
    }

    internal interface DownloadCallback {
        fun onSuccess(result: DownloadResult)
        fun onError(message: String)
    }

    private data class DraftSkin(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val downloadUrl: String,
        val version: String,
        val releaseUrl: String,
    )

    private data class ArchiveInfo(val preview: String = "", val localId: String = "")

    fun fetch(context: Context, callback: FetchCallback) {
        val appContext = context.applicationContext
        fetchExecutor.execute {
            try {
                val items = loadCatalog(appContext)
                mainHandler.post { callback.onSuccess(items) }
            } catch (t: Throwable) {
                val message = t.message?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: "Не удалось загрузить магазин"
                mainHandler.post { callback.onError(message) }
            }
        }
    }

    fun download(context: Context, url: String, callback: DownloadCallback) {
        val appContext = context.applicationContext
        downloadExecutor.execute {
            try {
                val bytes = httpGet(url, MAX_DOWNLOAD_BYTES)
                val info = GUPetSkinStore.importStream(appContext, ByteArrayInputStream(bytes))
                mainHandler.post {
                    callback.onSuccess(
                        DownloadResult(info.id, info.name, info.author, url)
                    )
                }
            } catch (t: Throwable) {
                val message = t.message?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: "Не удалось скачать скин"
                mainHandler.post { callback.onError(message) }
            }
        }
    }

    private fun loadCatalog(context: Context): List<RemoteSkin> {
        val json = String(
            httpGet(RELEASES_URL, MAX_API_BYTES, "application/vnd.github+json"),
            Charsets.UTF_8
        )
        val releases = JSONArray(json)
        val owned = GUPetSkinStore.list(context).map { it.id }.toSet()
        val drafts = mutableListOf<DraftSkin>()
        val seen = HashSet<String>()

        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            val version = release.optString("tag_name").trim()
                .ifBlank { release.optString("name").trim() }
            val releaseName = collapseWhitespace(release.optString("name"))
            val releaseUrl = release.optString("html_url").trim()
                .ifBlank { "https://github.com/GreatUserTeam/petckins/releases" }
            val (descriptionFromBody, bodyAuthor) =
                parseReleaseBody(release.optString("body"))
            val author = bodyAuthor ?: release.optJSONObject("author")
                ?.optString("login").orEmpty().trim().ifBlank { "GUTeam" }
            val assets = release.optJSONArray("assets") ?: continue
            val gupetAssets = mutableListOf<JSONObject>()

            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val filename = asset.optString("name").trim()
                val downloadUrl = asset.optString("browser_download_url").trim()
                if (!filename.lowercase(Locale.ROOT).endsWith(".gupet")) continue
                if (downloadUrl.isBlank()) continue
                gupetAssets.add(asset)
            }

            // Если в релизе один скин, человеческое имя релиза лучше имени файла.
            val releaseDisplayName = if (gupetAssets.size == 1 && releaseName.isNotBlank()) {
                releaseName.take(80)
            } else {
                null
            }

            for (asset in gupetAssets) {
                val filename = asset.optString("name").trim()
                val downloadUrl = asset.optString("browser_download_url").trim()
                val rawId = asset.opt("id")
                val assetId = when (rawId) {
                    is Number -> rawId.toLong().toString()
                    is String -> rawId.trim().takeIf { it.isNotBlank() && it != "null" }
                    else -> null
                } ?: "$version:$filename"
                if (!seen.add(assetId)) continue

                val assetStem = filename.replace(Regex("(?i)\\.gupet$"), "")
                val name = releaseDisplayName ?: prettifyName(assetStem)
                drafts.add(
                    DraftSkin(
                        id = assetId,
                        name = name,
                        author = author,
                        description = descriptionFromBody,
                        downloadUrl = downloadUrl,
                        version = version.ifBlank { "—" },
                        releaseUrl = releaseUrl,
                    )
                )
            }
        }

        // Превью — самый дорогой этап. Скачиваем архивы заранее в пуле из
        // восьми потоков, чтобы открытие магазина не растягивалось на минуты.
        val futures = drafts.map { draft ->
            previewExecutor.submit(Callable {
                runCatching {
                    inspectArchive(httpGet(draft.downloadUrl, MAX_DOWNLOAD_BYTES))
                }.getOrDefault(ArchiveInfo())
            })
        }

        return drafts.mapIndexed { index, draft ->
            val archive = runCatching { futures[index].get() }.getOrDefault(ArchiveInfo())
            val localId = archive.localId.takeIf { idPattern.matches(it) }.orEmpty()
            RemoteSkin(
                id = draft.id,
                name = draft.name,
                author = draft.author,
                description = draft.description,
                downloadUrl = draft.downloadUrl,
                version = draft.version,
                preview = archive.preview,
                releaseUrl = draft.releaseUrl,
                localId = localId,
                installed = localId.isNotEmpty() && owned.contains(localId),
            )
        }
    }

    private fun inspectArchive(bytes: ByteArray): ArchiveInfo {
        var preview = ""
        var localId = ""
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ARCHIVE_ENTRIES) break
                    if (!entry.isDirectory) {
                        when (entry.name) {
                            "idle.png" -> {
                                if (preview.isEmpty() &&
                                    (entry.size < 0 || entry.size <= MAX_PREVIEW_BYTES.toLong())
                                ) {
                                    val data = readEntry(zip, MAX_PREVIEW_BYTES)
                                    if (data != null && validPng(data)) {
                                        preview = "data:image/png;base64," +
                                            Base64.encodeToString(data, Base64.NO_WRAP)
                                    }
                                }
                            }
                            "manifest.json" -> {
                                if (localId.isEmpty() &&
                                    (entry.size < 0 || entry.size <= MAX_MANIFEST_BYTES.toLong())
                                ) {
                                    val data = readEntry(zip, MAX_MANIFEST_BYTES)
                                    if (data != null) {
                                        runCatching {
                                            val id = JSONObject(String(data, Charsets.UTF_8))
                                                .optString("id").trim()
                                            if (idPattern.matches(id)) localId = id
                                        }
                                    }
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        return ArchiveInfo(preview, localId)
    }

    private fun validPng(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth == GUPetSkinStore.WIDTH &&
            options.outHeight == GUPetSkinStore.HEIGHT
    }

    private fun readEntry(zip: ZipInputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun parseReleaseBody(body: String): Pair<String, String?> {
        val parts = mutableListOf<String>()
        var author: String? = null
        for (rawLine in body.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val separator = line.indexOf(':')
            if (separator >= 0) {
                val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                val value = line.substring(separator + 1).trim()
                when (key) {
                    "autor", "author" -> {
                        if (author == null && value.isNotEmpty()) author = value.take(80)
                        continue
                    }
                    "about" -> {
                        if (value.isNotEmpty()) parts.add(value)
                        continue
                    }
                }
            }
            parts.add(line)
        }
        val collapsed = collapseWhitespace(parts.joinToString(" "))
        if (collapsed.isEmpty()) {
            return Pair(
                "Скин для GUPet из официального магазина. / GUPet skin from the official store.",
                author,
            )
        }
        val description = if (collapsed.length > 240) {
            collapsed.take(240) + "…"
        } else {
            collapsed
        }
        return Pair(description, author)
    }

    private fun collapseWhitespace(text: String): String =
        text.split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private fun prettifyName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("(?i)\\.gupet$"), "")
            .replace(Regex("[_.-]"), " ")
        val collapsed = collapseWhitespace(cleaned)
        if (collapsed.isEmpty()) return "GUPet Skin"
        val out = StringBuilder(collapsed.length)
        var capitalize = true
        for (ch in collapsed) {
            if (ch.isWhitespace()) {
                capitalize = true
                out.append(ch)
                continue
            }
            if (capitalize) {
                out.append(ch.uppercaseChar())
                capitalize = false
            } else {
                out.append(ch)
            }
        }
        return out.toString().take(80)
    }

    private fun httpGet(
        url: String,
        limit: Int,
        accept: String? = null,
    ): ByteArray {
        if (!allowedHost(url)) throw IOException("Недопустимая ссылка на скин")
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", accept ?: "*/*")
            if (accept != null) {
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val details = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use {
                        it.readText().take(300)
                    }
                }.getOrNull()
                val suffix = details?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                val message = if (code == 403 || code == 429) {
                    "GitHub API временно ограничил запросы (лимит запросов)"
                } else {
                    "GitHub API вернул ошибку $code"
                }
                throw IOException(message + suffix)
            }
            val declared = connection.contentLength
            if (declared > limit) throw IOException("Файл слишком большой")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) {
                        throw IOException("Файл слишком большой")
                    }
                    output.write(buffer, 0, count)
                }
            }
            val bytes = output.toByteArray()
            if (bytes.isEmpty()) throw IOException("GitHub вернул пустой файл")
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun allowedHost(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host in setOf(
            "api.github.com",
            "github.com",
            "codeload.github.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "raw.githubusercontent.com",
        )
    }

    private fun daemonFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }
}
