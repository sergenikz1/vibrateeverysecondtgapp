package org.rutv.client.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.rutv.client.R
import org.rutv.client.data.MenuCard
import org.rutv.client.data.Repository
import org.rutv.client.data.VideoItem
import org.rutv.client.util.Ime

class SearchActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_container)
        requestMicrophoneIfNeeded()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, VideoSearchFragment())
                .commit()
        }
    }

    /** Пока открыта экранная клавиатура, «Назад» закрывает её, а не экран поиска. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (Ime.hideIfVisible(this)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Голосовой поиск в leanback работает только с выданным разрешением. */
    private fun requestMicrophoneIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }
}

class VideoSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val resultsAdapter = ArrayObjectAdapter(
        ClassPresenterSelector()
            .addClassPresenter(VideoItem::class.java, CardPresenter())
            .addClassPresenter(MenuCard::class.java, MenuCardPresenter())
    )

    private var query: String = ""
    private var page = 0
    private var hasNext = false
    private var loading = false
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(
            OnItemViewClickedListener { _, item, _, _ ->
                if (item is VideoItem) {
                    startActivity(DetailsActivity.intent(requireContext(), item))
                }
            }
        )
        setOnItemViewSelectedListener(
            OnItemViewSelectedListener { _, item, _, _ ->
                if (item is VideoItem &&
                    resultsAdapter.indexOf(item) >= resultsAdapter.size() - PREFETCH
                ) {
                    loadNextPage()
                }
            }
        )
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String): Boolean {
        scheduleSearch(newQuery, DEBOUNCE_MS)
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        scheduleSearch(query, 0L)
        return true
    }

    private fun scheduleSearch(newQuery: String, delayMs: Long) {
        val trimmed = newQuery.trim()
        searchJob?.cancel()
        if (trimmed.length < 2) {
            rowsAdapter.clear()
            resultsAdapter.clear()
            query = trimmed
            return
        }
        if (trimmed == query && resultsAdapter.size() > 0) return

        searchJob = lifecycleScope.launch {
            if (delayMs > 0) delay(delayMs)
            query = trimmed
            page = 0
            hasNext = true
            resultsAdapter.clear()
            rowsAdapter.clear()
            rowsAdapter.add(
                ListRow(
                    HeaderItem(0L, getString(R.string.search_results, trimmed)),
                    resultsAdapter
                )
            )
            loadNextPage()
        }
    }

    private fun loadNextPage() {
        if (loading || !hasNext || query.length < 2) return
        loading = true
        val requestedQuery = query
        lifecycleScope.launch {
            try {
                val result = Repository.search(requestedQuery, page + 1)
                if (requestedQuery != query) return@launch
                page = result.page
                hasNext = result.hasNext && result.items.isNotEmpty()
                val known = HashSet<String>()
                for (i in 0 until resultsAdapter.size()) {
                    (resultsAdapter.get(i) as? VideoItem)?.let { known.add(it.id) }
                }
                val fresh = result.items.filter { known.add(it.id) }
                if (fresh.isNotEmpty()) resultsAdapter.addAll(resultsAdapter.size(), fresh)
                if (resultsAdapter.size() == 0 && isAdded) showEmpty(requestedQuery)
            } catch (e: Exception) {
                if (resultsAdapter.size() == 0 && isAdded) showEmpty(requestedQuery)
                hasNext = false
            } finally {
                loading = false
            }
        }
    }

    /** Пустая выдача — не пустой экран: показываем понятную карточку-подсказку. */
    private fun showEmpty(query: String) {
        resultsAdapter.clear()
        resultsAdapter.add(
            MenuCard(
                MenuCard.ACTION_SEARCH,
                getString(R.string.search_empty, query),
                getString(R.string.search_empty_hint)
            )
        )
    }

    companion object {
        private const val DEBOUNCE_MS = 450L
        private const val PREFETCH = 6
    }
}
