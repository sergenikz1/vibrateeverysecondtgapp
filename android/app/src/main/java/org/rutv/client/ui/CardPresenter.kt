package org.rutv.client.ui

import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import org.rutv.client.R
import org.rutv.client.data.MenuCard
import org.rutv.client.data.VideoItem
import org.rutv.client.util.Format

/** Карточка видео в полке/сетке. */
class CardPresenter(
    private val widthRes: Int = R.dimen.card_width,
    private val heightRes: Int = R.dimen.card_height
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        val card = ImageCardView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(
                context.resources.getDimensionPixelSize(widthRes),
                context.resources.getDimensionPixelSize(heightRes)
            )
            setInfoAreaBackgroundColor(ContextCompat.getColor(context, R.color.background_card))
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val video = item as? VideoItem ?: return
        val card = viewHolder.view as ImageCardView
        val context = card.context

        card.titleText = video.title
        card.contentText = buildString {
            if (video.isLive) {
                append(context.getString(R.string.live_now))
            } else {
                Format.duration(video.durationSec).takeIf { it.isNotBlank() }?.let { append(it) }
            }
            val subtitle = Format.cardSubtitle(video.authorName, video.hits, video.published)
            if (subtitle.isNotBlank()) {
                if (isNotEmpty()) append(" • ")
                append(subtitle)
            }
        }
        if (video.isLive) {
            card.badgeImage = ContextCompat.getDrawable(context, R.drawable.ic_live_badge)
        }

        Glide.with(context)
            .load(video.thumbnail)
            .centerCrop()
            .placeholder(R.drawable.card_placeholder)
            .error(R.drawable.card_placeholder)
            .into(card.mainImageView)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        Glide.with(card.context).clear(card.mainImageView)
        card.badgeImage = null
        card.mainImage = null
    }
}

/** Карточка-действие («Поиск», «Настройки»). */
class MenuCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        val card = ImageCardView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(
                context.resources.getDimensionPixelSize(R.dimen.card_width),
                context.resources.getDimensionPixelSize(R.dimen.card_height)
            )
            setInfoAreaBackgroundColor(ContextCompat.getColor(context, R.color.background_card))
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val menu = item as? MenuCard ?: return
        val card = viewHolder.view as ImageCardView
        card.titleText = menu.title
        card.contentText = menu.subtitle
        val icon = when (menu.action) {
            MenuCard.ACTION_SEARCH -> R.drawable.ic_search
            MenuCard.ACTION_SETTINGS -> R.drawable.ic_settings
            else -> R.drawable.ic_refresh
        }
        card.mainImageView?.setImageResource(icon)
        card.mainImageView?.setBackgroundResource(R.drawable.default_background)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as ImageCardView).mainImage = null
    }
}
