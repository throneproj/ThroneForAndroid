package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.CrashHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.LogExport
import moe.matsuri.nb4a.utils.SendLog

/** Started by CrashHandler after a crash: shares a redacted log export, then finishes. */
class BlankActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SEND_LOG = "sendLog"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null || intent?.getBooleanExtra(EXTRA_SEND_LOG, false) != true) {
            finish()
            return
        }
        CrashHandler.reporting = true
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    LogExport.build(this@BlankActivity, redact = true, hideDestinations = false)
                }
                SendLog.share(this@BlankActivity, file, getString(R.string.log_crash_title))
            } catch (e: Exception) {
                Logs.w(e)
            }
            finish()
        }
    }

}
