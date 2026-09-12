package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app

object Theme {

    const val MONET = 0
    const val RED = 1
    const val PINK_SSR = 2
    const val PINK = 3
    const val PURPLE = 4
    const val DEEP_PURPLE = 5
    const val INDIGO = 6
    const val BLUE = 7
    const val LIGHT_BLUE = 8
    const val CYAN = 9
    const val TEAL = 10
    const val GREEN = 11
    const val LIGHT_GREEN = 12
    const val LIME = 13
    const val YELLOW = 14
    const val AMBER = 15
    const val ORANGE = 16
    const val DEEP_ORANGE = 17
    const val BROWN = 18
    const val GREY = 19
    const val BLUE_GREY = 20
    const val BLACK = 21
    const val VERDANT_MINT = 22
    const val WHITE = 23

    private fun defaultTheme() = PINK_SSR

    fun apply(context: Context) {
        context.setTheme(getTheme())
        applyAmoledOverlay(context)
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
        applyAmoledOverlay(context)
    }

    // 夜间模式下启用 AMOLED 纯黑开关时，在既有主题之上叠加纯黑 overlay
    private fun applyAmoledOverlay(context: Context) {
        if (DataStore.amoledTheme && usingNightMode()) {
            context.theme.applyStyle(R.style.Theme_SagerNet_Amoled, true)
        }
    }

    fun getTheme(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
            getTheme(MONET)
        } else {
            getTheme(DataStore.appTheme)
        }
    }

    fun getDialogTheme(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
            getDialogTheme(MONET)
        } else {
            getDialogTheme(DataStore.appTheme)
        }
    }

    fun getTheme(theme: Int): Int {
        return when (theme) {
            MONET -> R.style.Theme_SagerNet_Monet
            RED -> R.style.Theme_SagerNet_Red
            PINK -> R.style.Theme_SagerNet
            PINK_SSR -> R.style.Theme_SagerNet_Pink_SSR
            PURPLE -> R.style.Theme_SagerNet_Purple
            DEEP_PURPLE -> R.style.Theme_SagerNet_DeepPurple
            INDIGO -> R.style.Theme_SagerNet_Indigo
            BLUE -> R.style.Theme_SagerNet_Blue
            LIGHT_BLUE -> R.style.Theme_SagerNet_LightBlue
            CYAN -> R.style.Theme_SagerNet_Cyan
            TEAL -> R.style.Theme_SagerNet_Teal
            GREEN -> R.style.Theme_SagerNet_Green
            LIGHT_GREEN -> R.style.Theme_SagerNet_LightGreen
            LIME -> R.style.Theme_SagerNet_Lime
            YELLOW -> R.style.Theme_SagerNet_Yellow
            AMBER -> R.style.Theme_SagerNet_Amber
            ORANGE -> R.style.Theme_SagerNet_Orange
            DEEP_ORANGE -> R.style.Theme_SagerNet_DeepOrange
            BROWN -> R.style.Theme_SagerNet_Brown
            GREY -> R.style.Theme_SagerNet_Grey
            BLUE_GREY -> R.style.Theme_SagerNet_BlueGrey
            BLACK -> R.style.Theme_SagerNet_Black
            VERDANT_MINT -> R.style.Theme_SagerNet_VerdantMint
            WHITE ->
                // 纯白主题仅在非夜间模式生效，夜间模式回退纯黑主题避免纯白底色
                if (usingNightMode()) R.style.Theme_SagerNet_Black else R.style.Theme_SagerNet_White
            else -> getTheme(defaultTheme())
        }
    }

    fun getDialogTheme(theme: Int): Int {
        return when (theme) {
            MONET -> R.style.Theme_SagerNet_Dialog_Monet
            RED -> R.style.Theme_SagerNet_Dialog_Red
            PINK -> R.style.Theme_SagerNet_Dialog
            PINK_SSR -> R.style.Theme_SagerNet_Dialog_Pink_SSR
            PURPLE -> R.style.Theme_SagerNet_Dialog_Purple
            DEEP_PURPLE -> R.style.Theme_SagerNet_Dialog_DeepPurple
            INDIGO -> R.style.Theme_SagerNet_Dialog_Indigo
            BLUE -> R.style.Theme_SagerNet_Dialog_Blue
            LIGHT_BLUE -> R.style.Theme_SagerNet_Dialog_LightBlue
            CYAN -> R.style.Theme_SagerNet_Dialog_Cyan
            TEAL -> R.style.Theme_SagerNet_Dialog_Teal
            GREEN -> R.style.Theme_SagerNet_Dialog_Green
            LIGHT_GREEN -> R.style.Theme_SagerNet_Dialog_LightGreen
            LIME -> R.style.Theme_SagerNet_Dialog_Lime
            YELLOW -> R.style.Theme_SagerNet_Dialog_Yellow
            AMBER -> R.style.Theme_SagerNet_Dialog_Amber
            ORANGE -> R.style.Theme_SagerNet_Dialog_Orange
            DEEP_ORANGE -> R.style.Theme_SagerNet_Dialog_DeepOrange
            BROWN -> R.style.Theme_SagerNet_Dialog_Brown
            GREY -> R.style.Theme_SagerNet_Dialog_Grey
            BLUE_GREY -> R.style.Theme_SagerNet_Dialog_BlueGrey
            BLACK -> R.style.Theme_SagerNet_Dialog_Black
            VERDANT_MINT -> R.style.Theme_SagerNet_Dialog_VerdantMint
            WHITE ->
                if (usingNightMode()) R.style.Theme_SagerNet_Dialog_Black else R.style.Theme_SagerNet_Dialog_White
            else -> getDialogTheme(defaultTheme())
        }
    }

    // 纯白主题是否处于生效状态（夜间模式自动回退纯黑主题）
    fun isWhiteTheme(): Boolean {
        return DataStore.appTheme == WHITE && !usingNightMode()
    }

    // 当前主题主要颜色：纯白模式返回白色，其余从应用主题读取 colorPrimary
    fun getPrimaryColor(): Int {
        if (isWhiteTheme()) {
            return 0xFFFFFFFF.toInt()
        }
        val typedValue = TypedValue()
        app.theme.resolveAttribute(R.attr.colorPrimary, typedValue, true)
        return typedValue.data
    }

    var currentNightMode = -1
    fun getNightMode(): Int {
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int {
        return when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
        }
    }

    fun usingNightMode(): Boolean {
        return when (DataStore.nightTheme) {
            1 -> true
            2 -> false
            else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun applyNightTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }

}