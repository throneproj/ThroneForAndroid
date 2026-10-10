package io.nekohasekai.sagernet.bg.test

import io.nekohasekai.sagernet.bg.proto.CoreConfig
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * The tags the core tests in flight report, process-wide. The core's result buffers are global and a running test
 * claims every buffered result whose tag it asked for (testReporter, core/mobile/test.go), so two tests reporting one
 * tag at once could report each other's results; the desktop never streams two of them at once. A test holds its tags
 * while it runs and waits while another test holds any of them.
 */
internal object TestTags {

    /**
     * Held by every test of the running instance besides the tag it reports: a session's test-current does not know
     * the running config, so it and the stats bar take turns on this instead.
     */
    const val CURRENT = "\u0000current"

    private val held = MutableStateFlow<Set<String>>(emptySet())

    suspend fun <T> holding(tags: Collection<String>, block: suspend () -> T): T {
        val wanted = tags.toSet()
        while (true) {
            val taken = held.first { current -> wanted.none { it in current } }
            if (held.compareAndSet(taken, taken + wanted)) break
        }
        try {
            return block()
        } finally {
            held.update { it - wanted }
        }
    }

    /**
     * The tag a test of a box built from [coreConfig] reports with UseDefaultOutbound, picked the way sing-box picks
     * the default outbound (adapter/outbound/manager.go, box.go): `route.final`, else the first outbound (its index
     * when untagged), else the `direct` outbound it falls back to.
     */
    fun defaultOutbound(coreConfig: String): String = defaultOutbound(JsonInput.parseObject(coreConfig))

    /** The tag a test of the running instance built from [coreConfig] reports (prepareTestEnv's testCurrent). */
    fun ofCurrent(coreConfig: String): String {
        val config = JsonInput.parseObject(coreConfig)
        val hasProxy = listOf("outbounds", "endpoints").any { key ->
            config.array(key).any { (it as? JsonObject)?.string("tag") == CoreConfig.TAG_PROXY }
        }
        return if (hasProxy) CoreConfig.TAG_PROXY else defaultOutbound(config)
    }

    private fun defaultOutbound(config: JsonObject): String {
        val routeFinal = config.obj("route").string("final")
        if (routeFinal.isNotEmpty()) return routeFinal
        val first = config.array("outbounds").firstOrNull() as? JsonObject ?: return "direct"
        return first.string("tag").ifEmpty { "0" }
    }
}
