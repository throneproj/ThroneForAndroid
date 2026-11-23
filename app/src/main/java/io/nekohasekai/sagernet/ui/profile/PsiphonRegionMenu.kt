package io.nekohasekai.sagernet.ui.profile

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import io.nekohasekai.sagernet.bg.PsiphonRegions
import io.nekohasekai.sagernet.outbound.config.ExitThrough
import io.nekohasekai.sagernet.outbound.types.PsiphonChecks
import kotlinx.coroutines.launch
import java.text.Collator

/**
 * EditPsiphon::FillRegions (edit_psiphon.cpp), for the profile editor and the Psiphon exit: "Best performance" plus the
 * regions by name, the shipped list at once and the core's live list once :bg answers with one. A stored code outside
 * the list is kept as an extra entry.
 */
object PsiphonRegionMenu {

    fun bind(owner: LifecycleOwner, menu: ListPreference) {
        fill(menu, ExitThrough.PSIPHON_REGIONS)
        owner.lifecycleScope.launch {
            val live = PsiphonRegions.live()
            if (live.isNotEmpty()) fill(menu, live)
        }
    }

    private fun fill(menu: ListPreference, regions: List<String>) {
        val current = menu.value.orEmpty()
        val codes = regions.sortedWith(compareBy(Collator.getInstance()) { PsiphonChecks.regionName(it) }).toMutableList()
        if (current.isNotEmpty() && current !in codes) codes.add(current)
        menu.entries = (listOf(PsiphonChecks.regionName("")) + codes.map { PsiphonChecks.regionName(it) }).toTypedArray()
        menu.entryValues = (listOf("") + codes).toTypedArray()
        menu.refreshSummary()
    }
}
