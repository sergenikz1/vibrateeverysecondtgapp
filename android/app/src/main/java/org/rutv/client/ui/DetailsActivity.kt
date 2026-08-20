package org.rutv.client.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.widget.AbstractDetailsDescriptionPresenter
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DetailsOverviewRow
import androidx.leanback.widget.FullWidthDetailsOverviewRowPresenter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnActionClickedListener
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.launch
import org.rutv.client.R
import org.rutv.client.data.ChannelItem
import org.rutv.client.data.Prefs
import org.rutv.client.data.Repository
import org.rutv.client.data.VideoItem
import org.rutv.client.player.PlayerActivity
import org.rutv.client.util.Format

class DetailsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.details_container, VideoDetailsFragment())
                .commit()
        }
    }

    companion object {
        const val EXTRA_VIDEO = "extra_video"

        fun intent(context: Context, video: VideoItem): Intent =
            Intent(context, DetailsActivity::class.java).putExtra(EXTRA_VIDEO, video)
    }
}

/** Карточка видео: описание, действия и другие ролики канала. */
class VideoDetailsFragment : DetailsSupportFragment() {

    private lateinit var prefs: Prefs
    private lateinit var video: VideoItem
    private lateinit var rowsAdapter: ArrayObjectAdapter
    private lateinit var actionsAdapter: ArrayObjectAdapter

    private val overviewRow: DetailsOverviewRow by lazy { DetailsOverviewRow(video) }
    private val channelAdapter = ArrayObjectAdapter(CardPresenter())
    private var channelVideos: List<VideoItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(requireContext())
        val extra = requireActivity().intent.getSerializableExtra(DetailsActivity.EXTRA_VIDEO)
        video = extra as? VideoItem ?: run {
            requireActivity().finish()
            return
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!::video.isInitialized) return

        val rowPresenter = FullWidthDetailsOverviewRowPresenter(DetailsDescriptionPresenter()).apply {
            backgroundColor = ContextCompat.getColor(requireContext(), R.color.background_dark)
            actionsBackgroundColor =
                ContextCompat.getColor(requireContext(), R.color.background_card)
            onActionClickedListener = OnActionClickedListener { action -> onAction(action) }
        }

        val selector = ClassPresenterSelector().apply {
            addClassPresenter(DetailsOverviewRow::class.java, rowPresenter)
            addClassPresenter(ListRow::class.java, ListRowPresenter())
        }

        rowsAdapter = ArrayObjectAdapter(selector)
        actionsAdapter = ArrayObjectAdapter()
        rebuildActions()
        overviewRow.actionsAdapter = actionsAdapter
        overviewRow.imageDrawable =
            ContextCompat.getDrawable(requireContext(), R.drawable.card_placeholder)
        rowsAdapter.add(overviewRow)
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is VideoItem) {
                startActivity(DetailsActivity.intent(requireContext(), item))
            }
        }

        loadThumbnail()
        loadFullInfo()
        loadChannelVideos()
    }

    override fun onResume() {
        super.onResume()
        if (::actionsAdapter.isInitialized) rebuildActions()
    }

    private fun rebuildActions() {
        actionsAdapter.clear()
        val resume = prefs.position(video.id)
        val watchTitle = if (resume > 0) {
            getString(R.string.action_resume, Format.positionMs(resume))
        } else {
            getString(R.string.action_watch)
        }
        actionsAdapter.add(Action(ACTION_WATCH, watchTitle))
        if (resume > 0) {
            actionsAdapter.add(
                Action(ACTION_RESTART, getString(R.string.action_watch_from_start))
            )
        }
        actionsAdapter.add(
            Action(
                ACTION_FAVORITE,
                if (prefs.isFavorite(video.id)) getString(R.string.action_fav_remove)
                else getString(R.string.action_fav_add)
            )
        )
        if (video.authorId > 0L) {
            actionsAdapter.add(Action(ACTION_CHANNEL, getString(R.string.action_channel)))
            actionsAdapter.add(
                Action(
                    ACTION_PIN,
                    if (prefs.isPinned(video.authorId)) getString(R.string.action_unpin_channel)
                    else getString(R.string.action_pin_channel)
                )
            )
        }
    }

    private fun onAction(action: Action) {
        when (action.id) {
            ACTION_WATCH -> startActivity(
                PlayerActivity.intent(requireContext(), video, ArrayList(channelVideos))
            )
            ACTION_RESTART -> {
                prefs.savePosition(video.id, 0L, 0L)
                rebuildActions()
                startActivity(
                    PlayerActivity.intent(requireContext(), video, ArrayList(channelVideos))
                )
            }
            ACTION_FAVORITE -> {
                prefs.toggleFavorite(video)
                rebuildActions()
            }
            ACTION_CHANNEL -> startActivity(
                ChannelActivity.intent(
                    requireContext(),
                    ChannelItem(video.authorId, video.authorName ?: "", video.authorAvatar)
                )
            )
            ACTION_PIN -> {
                prefs.togglePinned(
                    ChannelItem(video.authorId, video.authorName ?: "", video.authorAvatar)
                )
                rebuildActions()
            }
        }
    }

    private fun loadThumbnail() {
        val url = video.thumbnail ?: return
        Glide.with(this)
            .asBitmap()
            .load(url)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    if (!isAdded) return
                    overviewRow.imageDrawable = BitmapDrawable(resources, resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) = Unit
            })
    }

    /** Описание в списочном API часто обрезано — дотягиваем карточку целиком. */
    private fun loadFullInfo() {
        if (video.description.length > 40) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val full = Repository.video(video.id)
                video = full.copy(
                    thumbnail = full.thumbnail ?: video.thumbnail,
                    authorName = full.authorName ?: video.authorName
                )
                overviewRow.item = video
            } catch (e: Exception) {
                // не критично: показываем то, что уже есть
            }
        }
    }

    private fun loadChannelVideos() {
        if (video.authorId <= 0L) return
        viewLifecycleOwner.lifecycleScope.launch {
            val items = Repository.channelVideosOrEmpty(video.authorId, 1)
                .filter { it.id != video.id }
            if (items.isEmpty() || !isAdded) return@launch
            channelVideos = items
            channelAdapter.clear()
            channelAdapter.addAll(0, items)
            if (rowsAdapter.size() == 1) {
                val header = video.authorName?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.action_channel)
                rowsAdapter.add(ListRow(HeaderItem(1L, header), channelAdapter))
            }
        }
    }

    private class DetailsDescriptionPresenter : AbstractDetailsDescriptionPresenter() {
        override fun onBindDescription(viewHolder: ViewHolder, item: Any?) {
            val video = item as? VideoItem ?: return
            viewHolder.title.text = video.title
            viewHolder.subtitle.text = Format.cardSubtitle(
                video.authorName,
                video.hits,
                video.published
            ).let { subtitle ->
                val duration = Format.duration(video.durationSec)
                if (duration.isNotBlank()) listOf(duration, subtitle).filter { it.isNotBlank() }
                    .joinToString(" • ") else subtitle
            }
            viewHolder.body.text = video.description
        }
    }

    companion object {
        private const val ACTION_WATCH = 1L
        private const val ACTION_FAVORITE = 2L
        private const val ACTION_CHANNEL = 3L
        private const val ACTION_PIN = 4L
        private const val ACTION_RESTART = 5L
    }
}
