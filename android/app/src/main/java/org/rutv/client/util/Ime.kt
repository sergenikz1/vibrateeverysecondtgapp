package org.rutv.client.util

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Экранная клавиатура на ТВ.
 *
 * Кнопка «Назад» при открытой клавиатуре должна закрывать саму клавиатуру,
 * а не экран, поэтому активность спрашивает [hideIfVisible] до обычной
 * обработки Back.
 */
object Ime {

    /**
     * Прячет клавиатуру, если она открыта.
     *
     * @return true, если клавиатура была открыта и событие нужно поглотить.
     */
    fun hideIfVisible(activity: Activity): Boolean {
        val view = activity.currentFocus ?: activity.window.decorView
        if (!isVisible(activity, view)) return false

        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
        WindowInsetsControllerCompat(activity.window, view).hide(WindowInsetsCompat.Type.ime())
        return true
    }

    /**
     * С Android 11 состояние клавиатуры видно из window insets. На более старых
     * версиях точного признака нет, поэтому считаем клавиатуру открытой, пока
     * ввод подключён к полю: первое нажатие Back закроет её, второе — экран.
     */
    private fun isVisible(activity: Activity, view: View): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = ViewCompat.getRootWindowInsets(view) ?: return false
            return insets.isVisible(WindowInsetsCompat.Type.ime())
        }
        val imm = activity.getSystemService(InputMethodManager::class.java)
        return imm?.isAcceptingText == true
    }
}
