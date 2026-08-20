package org.rutv.client.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Обёртка над [RutubeApi]: всё сетевое уходит в IO-диспетчер. */
object Repository {

    suspend fun feed(page: Int): Page = withContext(Dispatchers.IO) { RutubeApi.feed(page) }

    suspend fun channelVideos(personId: Long, page: Int): Page =
        withContext(Dispatchers.IO) { RutubeApi.channelVideos(personId, page) }

    suspend fun search(query: String, page: Int): Page =
        withContext(Dispatchers.IO) { RutubeApi.search(query, page) }

    suspend fun video(id: String): VideoItem = withContext(Dispatchers.IO) { RutubeApi.video(id) }

    suspend fun playback(id: String): PlaybackInfo =
        withContext(Dispatchers.IO) { RutubeApi.playback(id) }

    /** Не бросает исключений — пустой список вместо ошибки (для необязательных полок). */
    suspend fun feedOrEmpty(page: Int): List<VideoItem> = try {
        feed(page).items
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun channelVideosOrEmpty(personId: Long, page: Int): List<VideoItem> = try {
        channelVideos(personId, page).items
    } catch (e: Exception) {
        emptyList()
    }
}
