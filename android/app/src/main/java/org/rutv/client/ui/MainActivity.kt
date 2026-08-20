package org.rutv.client.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.rutv.client.R

/** Экран во весь экран умеет показывать «загрузка» и «ошибка + повторить». */
interface StatusHost {
    fun showLoading()
    fun showError(message: String, onRetry: () -> Unit)
    fun hideStatus()
}

class MainActivity : FragmentActivity(), StatusHost {

    private lateinit var panel: View
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var retry: Button

    private var retryAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        panel = findViewById(R.id.status_panel)
        progress = findViewById(R.id.status_progress)
        message = findViewById(R.id.status_text)
        retry = findViewById(R.id.status_retry)
        retry.setOnClickListener {
            val action = retryAction
            hideStatus()
            action?.invoke()
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, MainFragment())
                .commit()
        }
    }

    override fun showLoading() {
        retryAction = null
        message.setText(R.string.loading)
        progress.visibility = View.VISIBLE
        retry.visibility = View.GONE
        panel.visibility = View.VISIBLE
    }

    override fun showError(message: String, onRetry: () -> Unit) {
        retryAction = onRetry
        this.message.text = message
        progress.visibility = View.GONE
        retry.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        retry.requestFocus()
    }

    override fun hideStatus() {
        retryAction = null
        panel.visibility = View.GONE
    }
}
