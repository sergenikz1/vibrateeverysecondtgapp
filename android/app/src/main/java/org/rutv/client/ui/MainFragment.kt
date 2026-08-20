package org.rutv.client.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.rutv.client.R
import org.rutv.client.data.ChannelItem
import org.rutv.client.data.MenuCard
import org.rutv.client.data.Prefs
import org.rutv.client.data.Repository
import org.rutv.client.data.VideoItem

/** Главный экран: полки «Продолжить просмотр», «Избранное», каналы, лента. */
class MainFragment : BrowseSupportFragment() {

    private lateinit var prefs: Prefs
    private lateinit var rowsAdapter: ArrayObjectAdapter

    private val feedAdapter = ArrayObjectAdapter(CardPresenter())
    private val channelAdapters = LinkedHashMap<Long, ArrayObjectAdapter>()

    private var feedPage = 0
    private var feedHasNext = true
    private var feedLoading = false

    private val status: StatusHost?
        get() = activity as? StatusHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUi()
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = rowsAdapter
        rebuildRows()
        loadNextFeedPage()
        loadChannelRows()
    }

    override fun onResume() {
        super.onResume()
        // избранное/история могли поменяться на других экранах
        if (::rowsAdapter.isInitialized) rebuildRows()
    }

    private fun setupUi() {
        title = getString(R.string.browse_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.background_dark)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.brand_accent)

        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is VideoItem -> openVideo(item)
                is MenuCard -> onMenuCard(item)
            }
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, row ->
            val listRow = row as? ListRow ?: return@OnItemViewSelectedListener
            if (listRow.adapter === feedAdapter && item is VideoItem) {
                val index = feedAdapter.indexOf(item)
                if (index >= feedAdapter.size() - PREFETCH_THRESHOLD) loadNextFeedPage()
            }
        }
    }

    private fun openVideo(video: VideoItem) {
        startActivity(DetailsActivity.intent(requireContext(), video))
    }

    private fun onMenuCard(card: MenuCard) {
        when (card.action) {
            MenuCard.ACTION_SEARCH ->
                startActivity(Intent(requireContext(), SearchActivity::class.java))
            MenuCard.ACTION_SETTINGS ->
                startActivity(Intent(requireContext(), SettingsActivity::class.java))
            MenuCard.ACTION_REFRESH -> {
                feedAdapter.clear()
                feedPage = 0
                feedHasNext = true
                loadNextFeedPage()
                loadChannelRows(force = true)
            }
        }
    }

    /** Пересобирает набор полок (сами адаптеры данных переиспользуются). */
    private fun rebuildRows() {
        rowsAdapter.clear()
        var headerId = 1L

        val history = prefs.history()
        if (history.isNotEmpty()) {
            rowsAdapter.add(
                ListRow(
                    HeaderItem(headerId++, getString(R.string.row_history)),
                    ArrayObjectAdapter(CardPresenter()).apply { addAll(0, history.take(24)) }
                )
            )
        }

        val favorites = prefs.favorites()
        if (favorites.isNotEmpty()) {
            rowsAdapter.add(
                ListRow(
                    HeaderItem(headerId++, getString(R.string.row_favorites)),
                    ArrayObjectAdapter(CardPresenter()).apply { addAll(0, favorites) }
                )
            )
        }

        prefs.channels().forEach { channel ->
            val adapter = channelAdapters.getOrPut(channel.id) { ArrayObjectAdapter(CardPresenter()) }
            rowsAdapter.add(ListRow(HeaderItem(headerId++, channel.name), adapter))
        }

        rowsAdapter.add(ListRow(HeaderItem(headerId++, getString(R.string.row_new)), feedAdapter))

        val menu = ArrayObjectAdapter(MenuCardPresenter()).apply {
            add(
                MenuCard(
                    MenuCard.ACTION_SEARCH,
                    getString(R.string.action_search),
                    getString(R.string.menu_search_subtitle)
                )
            )
            add(
                MenuCard(
                    MenuCard.ACTION_SETTINGS,
                    getString(R.string.action_settings),
                    getString(R.string.menu_settings_subtitle)
                )
            )
            add(
                MenuCard(
                    MenuCard.ACTION_REFRESH,
                    getString(R.string.action_refresh),
                    getString(R.string.menu_refresh_subtitle)
                )
            )
        }
        rowsAdapter.add(ListRow(HeaderItem(headerId, getString(R.string.menu_row)), menu))
    }

    private fun loadNextFeedPage() {
        if (feedLoading || !feedHasNext) return
        feedLoading = true
        val firstPage = feedAdapter.size() == 0
        if (firstPage) status?.showLoading()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = Repository.feed(feedPage + 1)
                feedPage = page.page
                feedHasNext = page.hasNext && page.items.isNotEmpty()
                val known = HashSet<String>(feedAdapter.size())
                for (i in 0 until feedAdapter.size()) {
                    (feedAdapter.get(i) as? VideoItem)?.let { known.add(it.id) }
                }
                val fresh = page.items.filter { known.add(it.id) }
                if (fresh.isNotEmpty()) feedAdapter.addAll(feedAdapter.size(), fresh)
                status?.hideStatus()
            } catch (e: Exception) {
                feedHasNext = false
                if (!isAdded) return@launch
                val text = e.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.error_network)
                if (feedAdapter.size() == 0) {
                    status?.showError(text) {
                        feedHasNext = true
                        loadNextFeedPage()
                    }
                } else {
                    Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
                }
            } finally {
                feedLoading = false
            }
        }
    }

    private fun loadChannelRows(force: Boolean = false) {
        val channels: List<ChannelItem> = prefs.channels()
        if (channels.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            channels.forEach { channel ->
                val adapter = channelAdapters.getOrPut(channel.id) {
                    ArrayObjectAdapter(CardPresenter())
                }
                if (!force && adapter.size() > 0) return@forEach
                val items = Repository.channelVideosOrEmpty(channel.id, 1)
                adapter.clear()
                if (items.isNotEmpty()) adapter.addAll(0, items)
            }
        }
    }

    companion object {
        private const val PREFETCH_THRESHOLD = 6
    }
}
