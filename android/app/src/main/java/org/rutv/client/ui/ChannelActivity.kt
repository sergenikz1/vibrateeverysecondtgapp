package org.rutv.client.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.rutv.client.R
import org.rutv.client.data.ChannelItem
import org.rutv.client.data.Repository
import org.rutv.client.data.VideoItem

/** Все видео канала в виде сетки. */
class ChannelActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_container)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ChannelFragment())
                .commit()
        }
    }

    companion object {
        const val EXTRA_CHANNEL = "extra_channel"

        fun intent(context: Context, channel: ChannelItem): Intent =
            Intent(context, ChannelActivity::class.java).putExtra(EXTRA_CHANNEL, channel)
    }
}

class ChannelFragment : VerticalGridSupportFragment() {

    private lateinit var channel: ChannelItem
    private val itemsAdapter = ArrayObjectAdapter(
        CardPresenter(R.dimen.grid_card_width, R.dimen.grid_card_height)
    )

    private var page = 0
    private var hasNext = true
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extra = requireActivity().intent.getSerializableExtra(ChannelActivity.EXTRA_CHANNEL)
        channel = extra as? ChannelItem ?: run {
            requireActivity().finish()
            return
        }

        title = channel.name
        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply {
            numberOfColumns = COLUMNS
        }
        adapter = itemsAdapter

        // у VerticalGridSupportFragment для слушателей есть только сеттеры
        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is VideoItem) startActivity(DetailsActivity.intent(requireContext(), item))
        }
        setOnItemViewSelectedListener { _, item, _, _ ->
            if (item is VideoItem && itemsAdapter.indexOf(item) >= itemsAdapter.size() - COLUMNS * 2) {
                loadNext()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (::channel.isInitialized && itemsAdapter.size() == 0) loadNext()
    }

    private fun loadNext() {
        if (loading || !hasNext) return
        loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = Repository.channelVideos(channel.id, page + 1)
                page = result.page
                hasNext = result.hasNext && result.items.isNotEmpty()
                val known = HashSet<String>()
                for (i in 0 until itemsAdapter.size()) {
                    (itemsAdapter.get(i) as? VideoItem)?.let { known.add(it.id) }
                }
                val fresh = result.items.filter { known.add(it.id) }
                if (fresh.isNotEmpty()) itemsAdapter.addAll(itemsAdapter.size(), fresh)
            } catch (e: Exception) {
                hasNext = false
            } finally {
                loading = false
            }
        }
    }

    companion object {
        private const val COLUMNS = 5
    }
}
