package org.rutv.client.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Локальное хранилище: избранное, история, закреплённые каналы, настройки. */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("rutv", Context.MODE_PRIVATE)

    // region Избранное

    fun favorites(): List<VideoItem> = readVideos(KEY_FAVORITES)

    fun isFavorite(id: String): Boolean = favorites().any { it.id == id }

    fun toggleFavorite(video: VideoItem): Boolean {
        val list = favorites().toMutableList()
        val existed = list.removeAll { it.id == video.id }
        if (!existed) list.add(0, video)
        writeVideos(KEY_FAVORITES, list.take(MAX_ITEMS))
        return !existed
    }

    fun clearFavorites() = sp.edit().remove(KEY_FAVORITES).apply()

    // endregion

    // region История

    fun history(): List<VideoItem> = readVideos(KEY_HISTORY)

    fun rememberWatched(video: VideoItem) {
        val list = history().toMutableList()
        list.removeAll { it.id == video.id }
        list.add(0, video)
        writeVideos(KEY_HISTORY, list.take(MAX_ITEMS))
    }

    fun clearHistory() {
        sp.edit().remove(KEY_HISTORY).remove(KEY_POSITIONS).apply()
    }

    /** Позиция просмотра в миллисекундах. */
    fun position(videoId: String): Long = positions().optLong(videoId, 0L)

    fun savePosition(videoId: String, positionMs: Long, durationMs: Long) {
        val json = positions()
        val nearEnd = durationMs > 0 && positionMs > durationMs - 30_000
        if (positionMs < 15_000 || nearEnd) json.remove(videoId) else json.put(videoId, positionMs)
        sp.edit().putString(KEY_POSITIONS, json.toString()).apply()
    }

    private fun positions(): JSONObject = try {
        JSONObject(sp.getString(KEY_POSITIONS, "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    // endregion

    // region Каналы

    fun channels(): List<ChannelItem> {
        val out = ArrayList<ChannelItem>()
        val arr = readArray(KEY_CHANNELS)
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(ChannelItem.fromJson(it)) }
        }
        return out
    }

    fun isPinned(channelId: Long): Boolean = channels().any { it.id == channelId }

    fun togglePinned(channel: ChannelItem): Boolean {
        val list = channels().toMutableList()
        val existed = list.removeAll { it.id == channel.id }
        if (!existed) list.add(0, channel)
        val arr = JSONArray()
        list.take(20).forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY_CHANNELS, arr.toString()).apply()
        return !existed
    }

    // endregion

    // region Настройки

    /** 0 — авто, иначе максимальная высота кадра (720, 1080 …). */
    var maxHeight: Int
        get() = sp.getInt(KEY_MAX_HEIGHT, 0)
        set(value) = sp.edit().putInt(KEY_MAX_HEIGHT, value).apply()

    var autoplayNext: Boolean
        get() = sp.getBoolean(KEY_AUTOPLAY, true)
        set(value) = sp.edit().putBoolean(KEY_AUTOPLAY, value).apply()

    // endregion

    private fun readVideos(key: String): List<VideoItem> {
        val arr = readArray(key)
        val out = ArrayList<VideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(VideoItem.fromJson(it)) }
        }
        return out
    }

    private fun writeVideos(key: String, list: List<VideoItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(key, arr.toString()).apply()
    }

    private fun readArray(key: String): JSONArray = try {
        JSONArray(sp.getString(key, "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_HISTORY = "history"
        private const val KEY_POSITIONS = "positions"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_MAX_HEIGHT = "max_height"
        private const val KEY_AUTOPLAY = "autoplay_next"
        private const val MAX_ITEMS = 200

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context).also { instance = it }
        }
    }
}
