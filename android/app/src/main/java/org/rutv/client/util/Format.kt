package org.rutv.client.util

import java.util.Locale

/** Форматирование длительности, счётчиков и дат для карточек. */
object Format {

    fun duration(seconds: Int): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", m, s)
        }
    }

    fun positionMs(ms: Long): String = duration((ms / 1000L).toInt())

    fun views(count: Long): String = when {
        count <= 0L -> ""
        count < 1_000L -> "$count"
        count < 1_000_000L -> compact(count / 1_000.0) + " тыс."
        else -> compact(count / 1_000_000.0) + " млн"
    }

    /** 1.0 -> "1", 1.4 -> "1,4" (разделитель — как в текущей локали). */
    private fun compact(value: Double): String {
        val text = String.format(Locale.getDefault(), "%.1f", value)
        val separator = text.getOrNull(text.length - 2)
        return if (text.endsWith("0") && (separator == '.' || separator == ',')) {
            text.dropLast(2)
        } else {
            text
        }
    }

    /** "2024-05-01T10:20:30" -> "01.05.2024" */
    fun date(raw: String?): String {
        if (raw.isNullOrBlank() || raw.length < 10) return ""
        val d = raw.substring(0, 10).split("-")
        return if (d.size == 3) "${d[2]}.${d[1]}.${d[0]}" else ""
    }

    /** Подпись под карточкой: автор • просмотры • дата. */
    fun cardSubtitle(author: String?, hits: Long, published: String?): String {
        val parts = ArrayList<String>(3)
        if (!author.isNullOrBlank()) parts.add(author)
        views(hits).takeIf { it.isNotBlank() }?.let { parts.add("$it просм.") }
        date(published).takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return parts.joinToString(" • ")
    }
}
