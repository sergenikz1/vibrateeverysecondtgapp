package org.rutv.client.data

import android.net.Uri
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Тонкий клиент открытого JSON-API Rutube.
 *
 * Все запросы синхронные — вызывать только из фонового потока (см. [Repository]).
 * Реклама не запрашивается и не воспроизводится: приложение берёт из ответа
 * плейлиста только адрес контентного HLS-потока.
 */
object RutubeApi {

    const val BASE = "https://rutube.ru"
    const val REFERER = "https://rutube.ru/"
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Фильтр «обычных» видео, который использует сам сайт. */
    private const val ORIGIN_TYPES = "rtb,rst,ifrm,rspa,pv"

    class ApiException(message: String) : IOException(message)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful && body.isBlank()) {
                throw ApiException("HTTP ${response.code}")
            }
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                throw ApiException("Некорректный ответ сервера (HTTP ${response.code})")
            }
        }
    }

    // region Списки

    /** Лента свежих видео. */
    fun feed(page: Int): Page =
        fetchPage("$BASE/api/video/?page=$page&origin__type=$ORIGIN_TYPES", page)

    /** Видео конкретного канала. */
    fun channelVideos(personId: Long, page: Int): Page =
        fetchPage("$BASE/api/video/person/$personId/?page=$page&origin__type=$ORIGIN_TYPES", page)

    /** Поиск по названию. */
    fun search(query: String, page: Int): Page {
        val q = Uri.encode(query)
        return fetchPage("$BASE/api/search/video/?query=$q&page=$page", page)
    }

    /** Видео по тегу/рубрике. */
    fun tag(tagId: Long, page: Int): Page =
        fetchPage("$BASE/api/tags/video/$tagId/?page=$page", page)

    private fun fetchPage(url: String, page: Int): Page {
        val root = getJson(url)
        root.optString("detail").takeIf { it.isNotBlank() && !root.has("results") }?.let {
            throw ApiException(it)
        }
        val results = root.optJSONArray("results") ?: JSONArray()
        val items = ArrayList<VideoItem>(results.length())
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            parseVideo(o)?.let(items::add)
        }
        val hasNext = when {
            root.has("has_next") -> root.optBoolean("has_next")
            root.has("next") -> !root.isNull("next")
            else -> items.isNotEmpty()
        }
        return Page(items, hasNext, page)
    }

    // endregion

    /** Карточка одного видео. */
    fun video(videoId: String): VideoItem {
        val o = getJson("$BASE/api/video/$videoId/")
        o.optString("detail").takeIf { it.isNotBlank() && !o.has("title") }?.let {
            throw ApiException(it)
        }
        return parseVideo(o) ?: throw ApiException("Видео не найдено")
    }

    /**
     * Адрес готового к воспроизведению потока.
     *
     * Ответ `play/options` содержит и рекламные блоки, и контент; приложение
     * читает только контентный HLS и полностью игнорирует всё рекламное.
     */
    fun playback(videoId: String): PlaybackInfo {
        val url = "$BASE/api/play/options/$videoId/?format=json&no_404=true&referer=" +
            Uri.encode(REFERER) + "&pver=v2"
        val o = getJson(url)

        val detail = o.optString("detail")
        val balancer = o.optJSONObject("video_balancer")
        val live = o.optJSONObject("live_streams")

        val liveUrl = live?.let { extractUrl(it.opt("hls")) }
        if (!liveUrl.isNullOrBlank()) {
            return PlaybackInfo(liveUrl, true, o.optString("title").ifBlank { null })
        }

        val streamUrl = balancer?.let { b ->
            val keys = listOf("m3u8", "default", "json", "hls")
            keys.asSequence()
                .mapNotNull { extractUrl(b.opt(it)) }
                .firstOrNull { it.contains(".m3u8") }
                ?: b.keys().asSequence()
                    .mapNotNull { extractUrl(b.opt(it)) }
                    .firstOrNull { it.contains(".m3u8") || it.contains(".mpd") }
        }

        if (streamUrl.isNullOrBlank()) {
            if (detail.isNotBlank()) throw ApiException(detail)
            if (o.optBoolean("is_deleted")) throw ApiException("Видео удалено")
            throw ApiException("Для этого видео нет доступного потока")
        }
        return PlaybackInfo(streamUrl, false, o.optString("title").ifBlank { null })
    }

    private fun extractUrl(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.ifBlank { null }
        is JSONObject -> extractUrl(value.opt("url")) ?: extractUrl(value.opt("hls"))
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { extractUrl(value.opt(it)) }
            .firstOrNull()
        else -> null
    }

    // region Разбор

    fun parseVideo(o: JSONObject): VideoItem? {
        val id = firstNonBlank(
            o.optString("id"),
            o.optString("video_id"),
            parseVideoId(o.optString("video_url"))
        ) ?: return null

        val author = o.optJSONObject("author")
        return VideoItem(
            id = id,
            title = firstNonBlank(o.optString("title"), o.optString("name")) ?: "Без названия",
            description = o.optString("description"),
            thumbnail = firstNonBlank(
                o.optString("thumbnail_url"),
                o.optString("thumbnail"),
                o.optString("picture_url"),
                o.optJSONObject("pictures")?.optString("thumbnail_url")
            ),
            durationSec = o.optInt("duration", 0),
            authorId = author?.optLong("id") ?: 0L,
            authorName = author?.let { firstNonBlank(it.optString("name"), it.optString("title")) },
            authorAvatar = author?.let {
                firstNonBlank(it.optString("avatar_url"), it.optString("avatar"))
            },
            hits = o.optLong("hits", 0L),
            published = firstNonBlank(
                o.optString("publication_ts"),
                o.optString("created_ts")
            ),
            isLive = o.optBoolean("is_livestream", false) ||
                o.optString("type").equals("livestream", ignoreCase = true)
        )
    }

    /** Достаёт идентификатор видео из ссылки вида rutube.ru/video/<id>/. */
    fun parseVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val re = Regex("(?:video|shorts|play/embed)/([0-9a-fA-F]{16,40})")
        return re.find(url)?.groupValues?.getOrNull(1)
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() && it != "null" }

    // endregion
}
