package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.types.PsiphonChecks
import io.throneproj.mobile.Mobile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** The Psiphon egress regions the core reports (core/mobile/psiphon.go), on both sides of ICoreService.psiphonRegions. */
object PsiphonRegions {

    /** :bg: what Psiphon last reported in this process; empty until a Psiphon outbound has connected here. */
    fun reported(): Array<String> = try {
        val iterator = Mobile.psiphonRegions()
        val regions = ArrayList<String>()
        while (iterator.hasNext()) regions.add(iterator.next())
        regions.toTypedArray()
    } catch (e: Throwable) {
        Logs.w(e)
        emptyArray()
    }

    /**
     * editPsiphonCoreRegions (edit_psiphon.cpp): the live list as valid uppercase codes, empty when there is none.
     * Only a running :bg is asked: binding would start it just to load the core and answer with nothing.
     */
    suspend fun live(): List<String> {
        if (!bgRunning()) return emptyList()
        val reported = try {
            withContext(Dispatchers.IO) { CoreServiceClient.call { it.psiphonRegions() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
            return emptyList()
        }
        return reported.orEmpty()
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && PsiphonChecks.region(it).isEmpty() }
            .distinct()
    }

    private fun bgRunning(): Boolean {
        val name = app.packageName + ":bg"
        return SagerNet.activity.runningAppProcesses.orEmpty().any { it.processName == name }
    }
}
