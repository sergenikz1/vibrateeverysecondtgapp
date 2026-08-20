package org.rutv.client.ui

import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import org.rutv.client.R
import org.rutv.client.data.MenuCard
import org.rutv.client.data.Prefs
import org.rutv.client.data.VideoItem

/** Карточка видео в полке или сетке. */
class CardPresenter(
    private val widthRes: Int = R.dimen.card_width,
    private val heightRes: Int = R.dimen.card_height
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder =
        ViewHolder(VideoCardView(parent.context, widthRes, heightRes))

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val video = item as? VideoItem ?: return
        val card = viewHolder.view as VideoCardView

        val durationMs = video.durationSec * 1000L
        val watched = if (durationMs > 0L) {
            Prefs.get(card.context).position(video.id).toFloat() / durationMs
        } else {
            0f
        }
        card.bind(video, watched)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as VideoCardView).recycle()
    }
}

/** Карточка-действие («Поиск», «Настройки», «Обновить»). */
class MenuCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder =
        ViewHolder(VideoCardView(parent.context))

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val menu = item as? MenuCard ?: return
        val card = viewHolder.view as VideoCardView
        val icon = when (menu.action) {
            MenuCard.ACTION_SEARCH -> R.drawable.ic_search
            MenuCard.ACTION_SETTINGS -> R.drawable.ic_settings
            else -> R.drawable.ic_refresh
        }
        card.bindAction(menu.title, menu.subtitle, icon)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as VideoCardView).recycle()
    }
}
