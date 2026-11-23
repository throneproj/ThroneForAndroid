package io.nekohasekai.sagernet

import java.net.URI

/**
 * Speed-test values shared by the preferences and the :bg test engine. The setting `speed_test_mode` is the desktop's
 * int enum (TestConfig::SpeedTestMode, Const.hpp:39-49); the mode names label the live speed samples.
 */
object SpeedTestSettings {

    const val FULL = 0
    const val DOWNLOAD_ONLY = 1
    const val UPLOAD_ONLY = 2
    const val SIMPLE_DOWNLOAD = 3

    const val MODE_DOWNLOAD_UPLOAD = "download_upload"
    const val MODE_DOWNLOAD = "download"
    const val MODE_UPLOAD = "upload"
    const val MODE_SIMPLE_DOWNLOAD = "simple_download"

    /**
     * The name of a `speed_test_mode` value; an unknown value, like the removed "Only country" (4), is the desktop
     * default (download + upload).
     */
    fun modeName(mode: Int): String = when (mode) {
        DOWNLOAD_ONLY -> MODE_DOWNLOAD
        UPLOAD_ONLY -> MODE_UPLOAD
        SIMPLE_DOWNLOAD -> MODE_SIMPLE_DOWNLOAD
        else -> MODE_DOWNLOAD_UPLOAD
    }

    fun isValidHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
