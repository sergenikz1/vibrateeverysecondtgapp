package org.rutv.client.data

import java.io.Serializable
import org.json.JSONObject

/** Видео Rutube в том виде, в каком его показывает приложение. */
data class VideoItem(
    val id: String,
    val title: String,
    val description: String = "",
    val thumbnail: String? = null,
    val durationSec: Int = 0,
    val authorId: Long = 0L,
    val authorName: String? = null,
    val authorAvatar: String? = null,
    val hits: Long = 0L,
    val published: String? = null,
    val isLive: Boolean = false
) : Serializable {

    val pageUrl: String get() = "https://rutube.ru/video/$id/"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("description", description)
        put("thumbnail", thumbnail ?: "")
        put("duration", durationSec)
        put("author_id", authorId)
        put("author_name", authorName ?: "")
        put("author_avatar", authorAvatar ?: "")
        put("hits", hits)
        put("published", published ?: "")
        put("is_live", isLive)
    }

    companion object {
        private const val serialVersionUID = 1L

        fun fromJson(o: JSONObject): VideoItem = VideoItem(
            id = o.optString("id"),
            title = o.optString("title"),
            description = o.optString("description"),
            thumbnail = o.optString("thumbnail").ifBlank { null },
            durationSec = o.optInt("duration"),
            authorId = o.optLong("author_id"),
            authorName = o.optString("author_name").ifBlank { null },
            authorAvatar = o.optString("author_avatar").ifBlank { null },
            hits = o.optLong("hits"),
            published = o.optString("published").ifBlank { null },
            isLive = o.optBoolean("is_live")
        )
    }
}

/** Канал (автор) Rutube. */
data class ChannelItem(
    val id: Long,
    val name: String,
    val avatar: String? = null
) : Serializable {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("avatar", avatar ?: "")
    }

    companion object {
        private const val serialVersionUID = 1L

        fun fromJson(o: JSONObject): ChannelItem = ChannelItem(
            id = o.optLong("id"),
            name = o.optString("name"),
            avatar = o.optString("avatar").ifBlank { null }
        )
    }
}

/** Страница выдачи списочного API. */
data class Page(
    val items: List<VideoItem>,
    val hasNext: Boolean,
    val page: Int
)

/** Готовый к воспроизведению поток. */
data class PlaybackInfo(
    val url: String,
    val isLive: Boolean,
    val title: String?
)

/** Пункт меню на главном экране (карточка-действие). */
data class MenuCard(val action: Int, val title: String, val subtitle: String = "") : Serializable {
    companion object {
        const val ACTION_SEARCH = 1
        const val ACTION_SETTINGS = 2
        const val ACTION_REFRESH = 3
        private const val serialVersionUID = 1L
    }
}
