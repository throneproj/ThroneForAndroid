package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.applyTopInset

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    companion object {
        // The scrims androidx.activity uses for 3-button navigation before API 29 (from 29 the system draws one).
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    /** Dark status bar icons (a light toolbar colour). */
    var lightStatusBar = false
        private set

    // Before AppCompat derives this activity's configuration from the default night mode.
    override fun attachBaseContext(newBase: Context) {
        Theme.applyNightTheme()
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        if (!isDialog) enableEdgeToEdge(statusBarStyle(), navigationBarStyle())

        super.onCreate(savedInstanceState)

        uiMode = resources.configuration.uiMode
    }

    /** Status bar icons in contrast to the toolbar colour (dark icons on light bars such as the white theme's). */
    private fun statusBarStyle(): SystemBarStyle {
        val toolbar = MaterialColors.getColor(this, R.attr.colorPrimary, Color.BLACK)
        lightStatusBar = ColorUtils.calculateLuminance(toolbar) > 0.5
        return if (lightStatusBar) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
    }

    /** Navigation bar icons follow the window background; 3-button navigation keeps a scrim. */
    private fun navigationBarStyle() = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { Theme.usingNightMode() }

    override fun onContentChanged() {
        super.onContentChanged()
        findViewById<AppBarLayout>(R.id.appbar)?.applyTopInset()
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG)

}
