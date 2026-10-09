package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.types.XrayVless

/** The Xray-core VLESS outbound: uuid / encryption / flow, the Xray stream settings and the Xray mux. */
class XrayVlessSettingsActivity : BindingSettingsActivity<XrayVless>() {

    override fun createEntity() = XrayVless()
    override val preferencesResource = R.xml.xray_vless_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("uuid")
        pbm.text("encryption")
        pbm.text("flow")
        pbm.text("$STREAM.network")
        pbm.text("$STREAM.security")

        pbm.text("$STREAM.ws.host")
        pbm.text("$STREAM.ws.path")
        pbm.int("$STREAM.ws.ed")
        pbm.int("$STREAM.ws.heartbeatPeriod")
        pbm.text("$STREAM.ws.headers")

        pbm.text("$STREAM.httpupgrade.host")
        pbm.text("$STREAM.httpupgrade.path")
        pbm.int("$STREAM.httpupgrade.ed")
        pbm.text("$STREAM.httpupgrade.headers")

        pbm.text("$STREAM.grpc.serviceName")
        pbm.text("$STREAM.grpc.authority")
        pbm.bool("$STREAM.grpc.multiMode")

        pbm.text("$STREAM.xhttp.host")
        pbm.text("$STREAM.xhttp.path")
        pbm.text("$STREAM.xhttp.mode")
        pbm.text("$STREAM.xhttp.headers")
        pbm.text("$STREAM.xhttp.xPaddingBytes")
        pbm.bool("$STREAM.xhttp.noGRPCHeader")
        pbm.bool("$STREAM.xhttp.noSSEHeader")
        pbm.text("$STREAM.xhttp.scMaxEachPostBytes")
        pbm.text("$STREAM.xhttp.scMinPostsIntervalMs")
        pbm.int("$STREAM.xhttp.scMaxBufferedPosts")
        pbm.text("$STREAM.xhttp.scStreamUpServerSecs")
        pbm.text("$STREAM.xhttp.maxConcurrency")
        pbm.text("$STREAM.xhttp.maxConnections")
        pbm.text("$STREAM.xhttp.cMaxReuseTimes")
        pbm.text("$STREAM.xhttp.hMaxRequestTimes")
        pbm.text("$STREAM.xhttp.hMaxReusableSecs")
        pbm.int("$STREAM.xhttp.hKeepAlivePeriod")
        pbm.text("$STREAM.xhttp.downloadSettings")

        pbm.text("$STREAM.tls.serverName")
        pbm.text("$STREAM.tls.alpn")
        pbm.text("$STREAM.tls.fingerprint")
        pbm.text("$STREAM.tls.pinnedPeerCertSha256")
        pbm.text("$STREAM.tls.verifyPeerCertByName")
        pbm.text("$STREAM.tls.echConfigList")

        pbm.text("$STREAM.reality.serverName")
        pbm.text("$STREAM.reality.fingerprint")
        pbm.text("$STREAM.reality.password")
        pbm.text("$STREAM.reality.shortId")
        pbm.text("$STREAM.reality.spiderX")

        pbm.tri("multiplex.enabled", "multiplex.useDefault")
        pbm.int("multiplex.concurrency")
        pbm.int("multiplex.xudpConcurrency")

        jsonObjectText("$STREAM.finalmask", { streamSetting.finalmask }, { streamSetting.finalmask = it })
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("uuid")
        numberInput(
            "$STREAM.ws.ed", "$STREAM.ws.heartbeatPeriod", "$STREAM.httpupgrade.ed",
            "$STREAM.xhttp.scMaxBufferedPosts", "$STREAM.xhttp.hKeepAlivePeriod",
            "multiplex.concurrency", "multiplex.xudpConcurrency",
        )
        multilineInput(
            "$STREAM.ws.headers", "$STREAM.httpupgrade.headers", "$STREAM.xhttp.headers", "$STREAM.tls.alpn",
        )
        // empty inherits the uTLS preset (xrayStreamSetting.cpp:312, 377)
        presetMenuSummary("$STREAM.tls.fingerprint", DataStore.utlsFingerprint)
        presetMenuSummary("$STREAM.reality.fingerprint", DataStore.utlsFingerprint)
        presetTriSummary("multiplex.enabled", DataStore.xrayMuxDefaultOn)
        presetIntSummary("multiplex.concurrency", DataStore.xrayMuxConcurrency)

        val ws = findPreference<PreferenceCategory>("xrayWsCategory")
        val httpUpgrade = findPreference<PreferenceCategory>("xrayHttpUpgradeCategory")
        val grpc = findPreference<PreferenceCategory>("xrayGrpcCategory")
        val xhttp = findPreference<PreferenceCategory>("xrayXhttpCategory")
        onMenu("$STREAM.network") { network ->
            ws?.isVisible = network == "ws"
            httpUpgrade?.isVisible = network == "httpupgrade"
            grpc?.isVisible = network == "grpc"
            xhttp?.isVisible = network == "xhttp"
        }

        val tls = findPreference<PreferenceCategory>("xrayTlsCategory")
        val reality = findPreference<PreferenceCategory>("xrayRealityCategory")
        onMenu("$STREAM.security") { security ->
            tls?.isVisible = security == "tls"
            reality?.isVisible = security == "reality"
        }

        onMenu("multiplex.enabled") { setVisible(it == "1", "multiplex.concurrency", "multiplex.xudpConcurrency") }
    }

    companion object {
        private const val STREAM = "streamSetting"
    }

}
