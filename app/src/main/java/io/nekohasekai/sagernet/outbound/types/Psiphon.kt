package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.route.RuleSets
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr
import java.util.Locale

/** The Checks of psiphon.h. */
object PsiphonChecks {
    // QLocale::codeToTerritory also knows Kosovo's user-assigned XK.
    private val territories: Set<String> = Locale.getISOCountries().toHashSet().apply { add("XK") }

    /** Checks::PsiphonRegion (psiphon.cpp): empty, or the uppercase two-letter code of a known territory. */
    @JvmStatic
    fun region(code: String): String {
        if (code.isEmpty()) return ""
        if (code.length != 2 || !code.all { it in 'A'..'Z' } || code !in territories) return vtr(R.string.validation_psiphon_region)
        return ""
    }

    /** PsiphonRegionName (psiphon.cpp): "Best performance" for an empty code, else the territory's name. */
    @JvmStatic
    fun regionName(code: String): String {
        if (code.isEmpty()) return vtr(R.string.psiphon_region_best)
        val upper = code.uppercase(Locale.ROOT)
        if (upper !in territories) return upper
        return Locale.Builder().setRegion(upper).build().displayCountry.ifEmpty { upper }
    }
}

/**
 * psiphon (include/configs/outbounds/psiphon.h, src/configs/outbounds/psiphon.cpp): a Psiphon client the core runs on
 * Psiphon Inc.'s public network. The core pins its data folder and brings the network's own values, so the profile
 * holds only a region. No share link exists.
 */
class Psiphon : Outbound("psiphon") {
    /** Uppercase ISO 3166-1 alpha-2, as Psiphon spells it; empty for the best-performing region. */
    @JvmField var egress_region: String = ""

    override fun hasServerEndpoint(): Boolean = false

    /** psiphon::Validate (psiphon.cpp). */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "egress_region", PsiphonChecks.region(egress_region), egress_region)
    }

    /** psiphon::ParseFromJson (psiphon.cpp): a foreign outbound's network values and data folder are dropped with a warning. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "psiphon") return false
        super.parseFromJson(obj)
        server = ""
        serverPort = 0
        egress_region = obj.string("egress_region").trim().uppercase(Locale.ROOT)
        val ignored = FOREIGN_KEYS.filter { obj.contains(it) }
        if (ignored.isNotEmpty()) {
            ParseDiag.warn(ignored.first(), vtr(R.string.validation_psiphon_ignored, ignored.joinToString(", ")))
        }
        return true
    }

    /** psiphon.cpp: no link form. */
    override fun exportToLink(): String = ""

    /** psiphon::ExportToJson (psiphon.cpp). */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "psiphon"
        obj.merge(baseExportToJson())
        if (egress_region.isNotEmpty()) obj["egress_region"] = egress_region
        return obj
    }

    /** psiphon::ExportIdentity (psiphon.cpp): profiles of one region are the same server. */
    override fun exportIdentity(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "psiphon"
        if (egress_region.isNotEmpty()) obj["egress_region"] = egress_region
        return obj
    }

    /**
     * psiphon::Build (psiphon.cpp): no dial fields. The server lists are tried in order: the chosen jsDelivr mirror
     * gets through where GitHub is blocked, and Psiphon's own S3 copy comes last.
     */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "psiphon"
        if (egress_region.isNotEmpty()) {
            val message = PsiphonChecks.region(egress_region)
            if (message.isNotEmpty()) return BuildResult(JsonObject(), message)
            obj["egress_region"] = egress_region
        }
        val lists = ArrayList<String>()
        val mirrored = RuleSets.mirrorLink(LIST_GITHUB, ctx.rulesetMirror)
        if (mirrored != LIST_GITHUB) lists.add(mirrored)
        lists.add(LIST_GITHUB)
        lists.add(LIST_S3)
        obj["remote_server_list_urls"] = JsonValues.stringArray(lists)
        return BuildResult(obj)
    }

    override fun displayAddress(): String = PsiphonChecks.regionName(egress_region)

    override fun displayType(): String = "Psiphon"

    override fun security(): SecurityInfo = SecurityInfo("Encrypted", "", SecurityLevel.Secure)

    companion object {
        /** Signed by Psiphon Inc. and verified on download, so a mirror cannot alter it. */
        const val LIST_GITHUB = "https://raw.githubusercontent.com/throneproj/routeprofiles/psiphon/server_list_compressed"

        /** The double slash is part of Psiphon's real URL. */
        const val LIST_S3 = "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed"

        private val FOREIGN_KEYS = listOf(
            "data_directory", "propagation_channel_id", "sponsor_id", "remote_server_list_url", "remote_server_list_urls",
            "remote_server_list_signature_public_key", "obfuscated_server_list_root_urls", "network_id", "client_platform",
            "client_version", "upstream_proxy_url", "establish_tunnel_timeout", "limit_tunnel_protocols", "tunnel_pool_size",
        )
    }
}
