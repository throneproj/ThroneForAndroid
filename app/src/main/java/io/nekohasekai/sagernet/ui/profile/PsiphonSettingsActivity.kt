package io.nekohasekai.sagernet.ui.profile

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.types.Psiphon
import moe.matsuri.nb4a.ui.SimpleMenuPreference

/** EditPsiphon (edit_psiphon.cpp): a name and the egress region; the core brings everything else. */
class PsiphonSettingsActivity : BindingSettingsActivity<Psiphon>() {

    override fun createEntity() = Psiphon()
    override val preferencesResource = R.xml.psiphon_preferences

    init {
        pbm.text("name")
        pbm.text("egress_region")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        val region = findPreference<SimpleMenuPreference>("egress_region") ?: return
        region.summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> {
            (it.entry ?: it.value.orEmpty()).toString() + "\n" + getString(R.string.psiphon_egress_region_sum)
        }
        PsiphonRegionMenu.bind(this@PsiphonSettingsActivity, region)
    }

}
