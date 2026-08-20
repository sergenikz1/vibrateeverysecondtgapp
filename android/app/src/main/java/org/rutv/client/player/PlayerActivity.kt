package org.rutv.client.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import org.rutv.client.R
import org.rutv.client.data.Prefs
import org.rutv.client.data.Repository
import org.rutv.client.data.RutubeApi
import org.rutv.client.data.VideoItem

/**
 * Полноэкранное воспроизведение.
 *
 * Играет только контентный HLS-поток: рекламные вставки Rutube приходят
 * отдельным слоем плеера сайта и здесь просто не запрашиваются.
 */
@OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : FragmentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var playerView: PlayerView
    private lateinit var progress: ProgressBar
    private lateinit var errorPanel: View
    private lateinit var errorView: TextView
    private lateinit var retryButton: Button
    private lateinit var titleView: TextView

    private var player: ExoPlayer? = null
    private var video: VideoItem? = null
    private var queue: List<VideoItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        prefs = Prefs.get(this)
        playerView = findViewById(R.id.player_view)
        progress = findViewById(R.id.player_progress)
        errorPanel = findViewById(R.id.player_error_panel)
        errorView = findViewById(R.id.player_error)
        retryButton = findViewById(R.id.player_retry)
        titleView = findViewById(R.id.player_title)

        retryButton.setOnClickListener { preparePlayback() }

        // название показывается вместе с панелью управления
        playerView.setControllerVisibilityListener(
            object : PlayerView.ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    titleView.visibility =
                        if (visibility == View.VISIBLE && titleView.text.isNotBlank()) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                }
            }
        )

        @Suppress("UNCHECKED_CAST")
        queue = (intent.getSerializableExtra(EXTRA_QUEUE) as? ArrayList<VideoItem>) ?: emptyList()

        val extra = intent.getSerializableExtra(EXTRA_VIDEO) as? VideoItem
        if (extra != null) {
            video = extra
            preparePlayback()
        } else {
            val id = RutubeApi.parseVideoId(intent.dataString)
            if (id.isNullOrBlank()) {
                showError(getString(R.string.error_playback))
            } else {
                lifecycleScope.launch {
                    try {
                        video = Repository.video(id)
                        preparePlayback()
                    } catch (e: Exception) {
                        showError(e.message ?: getString(R.string.error_playback))
                    }
                }
            }
        }
    }

    private fun preparePlayback() {
        val item = video ?: return
        titleView.text = item.title
        showLoading()
        lifecycleScope.launch {
            try {
                val info = Repository.playback(item.id)
                prefs.rememberWatched(item)
                startPlayer(info.url, info.isLive)
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_no_stream))
            }
        }
    }

    private fun startPlayer(url: String, isLive: Boolean) {
        releasePlayer()

        val dataSourceFactory = OkHttpDataSource.Factory(RutubeApi.http)
            .setUserAgent(RutubeApi.USER_AGENT)
            .setDefaultRequestProperties(mapOf("Referer" to RutubeApi.REFERER))

        val trackSelector = DefaultTrackSelector(this).apply {
            val maxHeight = prefs.maxHeight
            if (maxHeight > 0) {
                setParameters(buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, maxHeight))
            }
        }

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setTrackSelector(trackSelector)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                progress.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (playbackState == Player.STATE_READY) errorPanel.visibility = View.GONE
                if (playbackState == Player.STATE_ENDED) onPlaybackEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                showError(getString(R.string.error_playback) + "\n" + error.errorCodeName)
            }
        })

        playerView.player = exo
        playerView.requestFocus()
        exo.setMediaItem(MediaItem.fromUri(url))
        val resume = video?.let { prefs.position(it.id) } ?: 0L
        if (!isLive && resume > 0L) exo.seekTo(resume)
        exo.playWhenReady = true
        exo.prepare()
        player = exo
        errorPanel.visibility = View.GONE
    }

    private fun onPlaybackEnded() {
        val current = video ?: return
        prefs.savePosition(current.id, 0L, 0L)
        if (!prefs.autoplayNext) {
            finish()
            return
        }
        val next = nextInQueue(current)
        if (next == null) {
            finish()
        } else {
            video = next
            preparePlayback()
        }
    }

    private fun nextInQueue(current: VideoItem): VideoItem? {
        if (queue.isEmpty()) return null
        val index = queue.indexOfFirst { it.id == current.id }
        return when {
            index < 0 -> queue.firstOrNull { it.id != current.id }
            index + 1 < queue.size -> queue[index + 1]
            else -> null
        }
    }

    private fun showLoading() {
        progress.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        errorView.text = message
        errorPanel.visibility = View.VISIBLE
        retryButton.requestFocus()
    }

    private fun savePosition() {
        val exo = player ?: return
        val item = video ?: return
        if (exo.duration > 0) {
            prefs.savePosition(item.id, exo.currentPosition, exo.duration)
        }
    }

    private fun releasePlayer() {
        player?.let { exo ->
            savePosition()
            exo.release()
        }
        player = null
        playerView.player = null
    }

    override fun onPause() {
        super.onPause()
        savePosition()
        player?.pause()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val exo = player
        if (exo != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlay(exo)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    exo.play()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    exo.pause()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (!playerView.isControllerFullyVisible) {
                        seekBy(exo, -SEEK_STEP_MS)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (!playerView.isControllerFullyVisible) {
                        seekBy(exo, SEEK_STEP_MS)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!playerView.isControllerFullyVisible) {
                        togglePlay(exo)
                        playerView.showController()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun togglePlay(exo: ExoPlayer) {
        if (exo.isPlaying) exo.pause() else exo.play()
        playerView.showController()
    }

    private fun seekBy(exo: ExoPlayer, deltaMs: Long) {
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        val duration = exo.duration
        exo.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        playerView.showController()
    }

    companion object {
        const val EXTRA_VIDEO = "extra_video"
        const val EXTRA_QUEUE = "extra_queue"
        private const val SEEK_STEP_MS = 10_000L

        fun intent(
            context: Context,
            video: VideoItem,
            queue: ArrayList<VideoItem> = ArrayList()
        ): Intent = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_VIDEO, video)
            .putExtra(EXTRA_QUEUE, queue)
    }
}
