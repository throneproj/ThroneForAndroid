package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity

class ProfileSelectActivity : ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_PROFILE_ID = "id"

        /** Lists auto selectors too; without it the picker fills a fixed-server slot (front / landing proxy). */
        const val EXTRA_ALLOW_AUTO_SELECTORS = "allowAutoSelectors"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) return
        val selected = intent.getParcelableExtra<ProxyEntity>(EXTRA_SELECTED)
        val hideAutoSelectors = !intent.getBooleanExtra(EXTRA_ALLOW_AUTO_SELECTORS, false)

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment.forSelection(selected, R.string.select_profile, hideAutoSelectors = hideAutoSelectors)
            )
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_ID, profileId)
        })
        finish()
    }

}
