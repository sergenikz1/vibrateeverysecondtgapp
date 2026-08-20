package org.rutv.client.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import org.rutv.client.R
import org.rutv.client.data.VideoItem
import org.rutv.client.util.Format

/**
 * Карточка видео: постер, длительность поверх него, полоса досмотра,
 * название в две строки и подпись с автором и просмотрами.
 */
class VideoCardView(
    context: Context,
    widthRes: Int = R.dimen.card_width,
    heightRes: Int = R.dimen.card_height
) : LinearLayout(context) {

    private val poster: FrameLayout
    private val image: ImageView
    private val badge: TextView
    private val progress: ProgressBar
    private val title: TextView
    private val subtitle: TextView

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.card_background)
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS

        LayoutInflater.from(context).inflate(R.layout.view_video_card, this, true)

        poster = findViewById(R.id.card_poster)
        image = findViewById(R.id.card_image)
        badge = findViewById(R.id.card_badge)
        progress = findViewById(R.id.card_progress)
        title = findViewById(R.id.card_title)
        subtitle = findViewById(R.id.card_subtitle)

        val width = resources.getDimensionPixelSize(widthRes)
        val height = resources.getDimensionPixelSize(heightRes)
        poster.layoutParams = LayoutParams(width, height)
        layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /**
     * @param watchedFraction доля просмотренного от 0 до 1; 0 — полоса не показывается.
     */
    fun bind(video: VideoItem, watchedFraction: Float) {
        title.text = video.title
        subtitle.text = Format.cardSubtitle(video.authorName, video.hits, video.published)

        when {
            video.isLive -> {
                badge.text = context.getString(R.string.live_now)
                badge.setBackgroundResource(R.drawable.badge_pill_live)
                badge.visibility = View.VISIBLE
            }
            video.durationSec > 0 -> {
                badge.text = Format.duration(video.durationSec)
                badge.setBackgroundResource(R.drawable.badge_pill)
                badge.visibility = View.VISIBLE
            }
            else -> badge.visibility = View.GONE
        }

        if (watchedFraction > 0f) {
            progress.progress = (watchedFraction.coerceIn(0f, 1f) * 100).toInt()
            progress.visibility = View.VISIBLE
        } else {
            progress.visibility = View.GONE
        }

        Glide.with(context)
            .load(video.thumbnail)
            .centerCrop()
            .placeholder(R.drawable.card_placeholder)
            .error(R.drawable.card_placeholder)
            .into(image)
    }

    /** Карточка-действие: иконка вместо постера и заголовок без подписи. */
    fun bindAction(actionTitle: String, subtitleText: String, iconRes: Int) {
        title.text = actionTitle
        subtitle.text = subtitleText
        badge.visibility = View.GONE
        progress.visibility = View.GONE
        Glide.with(context).clear(image)
        image.setImageResource(iconRes)
        image.scaleType = ImageView.ScaleType.CENTER_INSIDE
        image.setBackgroundColor(ContextCompat.getColor(context, R.color.background_dark))
    }

    fun recycle() {
        Glide.with(context).clear(image)
        image.setImageResource(R.drawable.card_placeholder)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.background = null
        badge.visibility = View.GONE
        progress.visibility = View.GONE
    }
}
