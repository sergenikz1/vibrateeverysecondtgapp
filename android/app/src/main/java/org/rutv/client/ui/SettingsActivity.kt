package org.rutv.client.ui

import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import org.rutv.client.BuildConfig
import org.rutv.client.R
import org.rutv.client.data.Prefs

class SettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, SettingsFragment(), android.R.id.content)
        }
    }
}

class SettingsFragment : GuidedStepSupportFragment() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(requireContext())
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(
            getString(R.string.settings_title),
            getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME,
            null,
            null
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_QUALITY)
                .title(R.string.settings_quality)
                .description(qualityLabel(prefs.maxHeight))
                .subActions(qualitySubActions())
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_AUTOPLAY)
                .title(R.string.settings_autoplay)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(prefs.autoplayNext)
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CLEAR_HISTORY)
                .title(R.string.settings_clear_history)
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CLEAR_FAVORITES)
                .title(R.string.settings_clear_favorites)
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_ABOUT)
                .title(R.string.settings_about)
                .description(
                    "Неофициальный клиент Rutube для телевизоров. " +
                        "Версия " + BuildConfig.VERSION_NAME
                )
                .infoOnly(true)
                .focusable(false)
                .build()
        )
    }

    private fun qualitySubActions(): List<GuidedAction> = QUALITIES.map { height ->
        GuidedAction.Builder(requireContext())
            .id(SUB_QUALITY_BASE + height)
            .title(qualityLabel(height))
            .checkSetId(QUALITY_CHECK_SET)
            .checked(prefs.maxHeight == height)
            .build()
    }

    private fun qualityLabel(height: Int): String =
        if (height <= 0) "Авто (лучшее доступное)" else "До ${height}p"

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_AUTOPLAY -> prefs.autoplayNext = action.isChecked
            ACTION_CLEAR_HISTORY -> {
                prefs.clearHistory()
                toast(getString(R.string.settings_cleared))
            }
            ACTION_CLEAR_FAVORITES -> {
                prefs.clearFavorites()
                toast(getString(R.string.settings_cleared))
            }
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        if (action.id >= SUB_QUALITY_BASE) {
            val height = (action.id - SUB_QUALITY_BASE).toInt()
            prefs.maxHeight = height
            findActionById(ACTION_QUALITY)?.let { parent ->
                parent.description = qualityLabel(height)
                notifyActionChanged(findActionPositionById(ACTION_QUALITY))
            }
        }
        return true
    }

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ACTION_QUALITY = 1L
        private const val ACTION_AUTOPLAY = 2L
        private const val ACTION_CLEAR_HISTORY = 3L
        private const val ACTION_CLEAR_FAVORITES = 4L
        private const val ACTION_ABOUT = 5L

        private const val SUB_QUALITY_BASE = 1000L
        private const val QUALITY_CHECK_SET = 10
        private val QUALITIES = listOf(0, 360, 480, 720, 1080)
    }
}
