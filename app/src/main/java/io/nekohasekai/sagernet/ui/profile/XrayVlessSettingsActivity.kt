package io.nekohasekai.sagernet.ui.profile

import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.common.ResolverChecks
import io.nekohasekai.sagernet.outbound.common.XrayChecks
import io.nekohasekai.sagernet.outbound.common.XrayKcp
import io.nekohasekai.sagernet.outbound.common.XrayXDrive
import io.nekohasekai.sagernet.outbound.common.normalizeXrayNetwork
import io.nekohasekai.sagernet.outbound.common.upgradeXrayFinalmask
import io.nekohasekai.sagernet.outbound.common.xrayFinalmaskHasXdns
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.types.XrayVless
import io.nekohasekai.sagernet.outbound.types.XrayVlessChecks
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.ui.json.ProfileJson
import io.nekohasekai.sagernet.validation.CommonChecks
import io.nekohasekai.sagernet.validation.Validation

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

        for ((name, _) in XrayKcp.VALUES) pbm.int("$STREAM.kcp.$name")
        pbm.text("$STREAM.xdnsResolvers.lines", XDNS_RESOLVERS)

        pbm.text(XDRIVE_SERVICE)
        pbm.text("$STREAM.xdrive.remoteFolder")
        pbm.text(S3_ENDPOINT)
        pbm.text(S3_REGION)
        pbm.text(S3_BUCKET)
        pbm.bool(S3_PATH_STYLE)
        pbm.text("$STREAM.xdrive.frontAddress")
        pbm.int(XDRIVE_FRONT_PORT)
        for ((name, _) in XrayXDrive.TUNING) pbm.int("$STREAM.xdrive.$name")

        pbm.text("$STREAM.tls.serverName")
        pbm.text("$STREAM.tls.alpn")
        pbm.text("$STREAM.tls.fingerprint")
        pbm.text("$STREAM.tls.pinnedPeerCertSha256")
        pbm.text("$STREAM.tls.verifyPeerCertByName")
        pbm.text("$STREAM.tls.echConfigList")
        pbm.text("$STREAM.tls.cipherSuites")

        pbm.text("$STREAM.reality.serverName")
        pbm.text("$STREAM.reality.fingerprint")
        pbm.text("$STREAM.reality.password")
        pbm.text("$STREAM.reality.shortId")
        pbm.text("$STREAM.reality.spiderX")

        XrayMuxBlock.bind(pbm)

        // dialog_edit_profile_xray.cpp: the editor keeps the finalmask in the spellings Xray reads now.
        jsonObjectText(FINALMASK, { streamSetting.finalmask.copy().also { upgradeXrayFinalmask(it) } }, {
            streamSetting.finalmask = it.also { upgradeXrayFinalmask(it) }
        })
        jsonObjectText(XDRIVE_TEMPLATE, { streamSetting.xdrive.templateSpec }, { streamSetting.xdrive.templateSpec = it })
    }

    // dialog_edit_profile_xdrive.cpp: one secrets list backs the Google Drive rows, the S3 rows and the template's
    // list, and an unset port or tuning value shows as the Xray default; so does an mKCP value at its default.
    override fun XrayVless.writeUnboundToCache() {
        val store = DataStore.profileCacheStore
        val secrets = streamSetting.xdrive.secrets
        GOOGLE_DRIVE_SECRETS.forEachIndexed { i, key -> store.putString(key, secrets.getOrElse(i) { "" }) }
        S3_SECRETS.forEachIndexed { i, key -> store.putString(key, secrets.getOrElse(i) { "" }) }
        store.putString(XDRIVE_SECRETS, secrets.joinToString("\n"))
        for (key in listOf(XDRIVE_FRONT_PORT) + XrayXDrive.TUNING.map { "$STREAM.xdrive.${it.first}" }) {
            if (store.getString(key)?.trim() == "0") store.putString(key, "")
        }
        for ((name, property) in XrayKcp.VALUES) {
            if (property.get(streamSetting.kcp) == XrayKcp.DEFAULTS[name]) store.putString("$STREAM.kcp.$name", "")
        }
    }

    override fun XrayVless.readUnboundFromCache() {
        // saveXrayKCP: an empty row is Xray's default
        for ((name, property) in XrayKcp.VALUES) {
            if (cached("$STREAM.kcp.$name").isEmpty()) property.set(streamSetting.kcp, XrayKcp.DEFAULTS.getValue(name))
        }
        if (normalizeXrayNetwork(streamSetting.network) != "xdrive") return
        val xdrive = streamSetting.xdrive
        // saveXrayXDrive trims these: a pasted folder ID with a trailing space would find nothing on the Drive
        xdrive.service = xdrive.service.trim()
        xdrive.remoteFolder = xdrive.remoteFolder.trim()
        xdrive.s3Endpoint = xdrive.s3Endpoint.trim()
        xdrive.s3Region = xdrive.s3Region.trim()
        xdrive.s3Bucket = xdrive.s3Bucket.trim()
        xdrive.frontAddress = xdrive.frontAddress.trim()
        xdrive.secrets = secretsFromUi(xdrive.service, xdrive.secrets)
    }

    /** xdriveSecretsFromUi (dialog_edit_profile_xdrive.cpp): the rows [service] shows; any other service keeps [stored]. */
    private fun secretsFromUi(service: String, stored: List<String>): MutableList<String> {
        val store = DataStore.profileCacheStore
        return when (service) {
            "template" -> store.getString(XDRIVE_SECRETS).orEmpty().split('\n').map { it.trim() }
                .filterTo(ArrayList()) { it.isNotEmpty() }
            "s3" -> {
                val keys = S3_SECRETS.take(2).mapTo(ArrayList()) { store.getString(it).orEmpty().trim() }
                val token = store.getString(S3_SECRETS[2]).orEmpty().trim()
                if (token.isNotEmpty()) keys.add(token)
                keys
            }
            // Xray takes exactly these three, so entries a list carried past them are dropped.
            "google-drive" -> GOOGLE_DRIVE_SECRETS.mapTo(ArrayList()) { store.getString(it).orEmpty().trim() }
            else -> ArrayList(stored)
        }
    }

    /** Set once the screen exists: re-reads the finalmask the JSON editor may have changed. */
    private var finalmaskEdited: (() -> Unit)? = null

    override fun onResume() {
        finalmaskEdited?.invoke()
        super.onResume()
    }

    private fun cached(key: String) = DataStore.profileCacheStore.getString(key)?.trim().orEmpty()

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("uuid")
        numberInput(
            "$STREAM.ws.ed", "$STREAM.ws.heartbeatPeriod", "$STREAM.httpupgrade.ed",
            "$STREAM.xhttp.scMaxBufferedPosts", "$STREAM.xhttp.hKeepAlivePeriod",
        )
        multilineInput(
            "$STREAM.ws.headers", "$STREAM.httpupgrade.headers", "$STREAM.xhttp.headers", "$STREAM.tls.alpn",
        )
        // empty inherits the uTLS preset (xrayStreamSetting.cpp:312, 377)
        presetMenuSummary("$STREAM.tls.fingerprint", DataStore.utlsFingerprint)
        presetMenuSummary("$STREAM.reality.fingerprint", DataStore.utlsFingerprint)
        cipherSuitesInput("$STREAM.tls.cipherSuites", ":")
        XrayMuxBlock.setup(this)

        val ws = findPreference<PreferenceCategory>("xrayWsCategory")
        val httpUpgrade = findPreference<PreferenceCategory>("xrayHttpUpgradeCategory")
        val grpc = findPreference<PreferenceCategory>("xrayGrpcCategory")
        val xhttp = findPreference<PreferenceCategory>("xrayXhttpCategory")
        val xdrive = findPreference<PreferenceCategory>("xrayXDriveCategory")
        val xdriveTuning = findPreference<PreferenceCategory>("xrayXDriveTuningCategory")
        val kcp = findPreference<PreferenceCategory>("xrayKcpCategory")
        val kcpTuning = findPreference<PreferenceCategory>("xrayKcpTuningCategory")
        // dialog_edit_profile_xray.cpp updateXrayRows: the resolver lines only while finalmask has an xdns mask
        var network = ""
        var xdns = false
        fun xdnsRow() = setVisible(network == "kcp" && xdns, XDNS_RESOLVERS)
        fun readFinalmask() {
            val store = DataStore.profileCacheStore
            val finalmask = JsonInput.parseObject(store.getString(FINALMASK).orEmpty())
            if (upgradeXrayFinalmask(finalmask)) store.putString(FINALMASK, ProfileJson.text(finalmask))
            xdns = xrayFinalmaskHasXdns(finalmask)
        }
        readFinalmask()
        finalmaskEdited = {
            readFinalmask()
            xdnsRow()
            form?.refresh()
        }
        onMenu("$STREAM.network") { selected ->
            network = selected
            ws?.isVisible = network == "ws"
            httpUpgrade?.isVisible = network == "httpupgrade"
            grpc?.isVisible = network == "grpc"
            xhttp?.isVisible = network == "xhttp"
            kcp?.isVisible = network == "kcp"
            kcpTuning?.isVisible = network == "kcp"
            xdrive?.isVisible = network == "xdrive"
            xdriveTuning?.isVisible = network == "xdrive"
            xdnsRow()
        }
        for ((name, xrayDefault) in XrayKcp.DEFAULTS) {
            numberInput("$STREAM.kcp.$name")
            presetTextSummary("$STREAM.kcp.$name", "", xrayDefault.toString())
        }
        multilineInput(XDNS_RESOLVERS)

        // dialog_edit_profile_xdrive.cpp updateXrayXDriveControls
        findPreference<ListPreference>(XDRIVE_SERVICE)?.keepUnlistedValue()
        onMenu(XDRIVE_SERVICE) { service ->
            setVisible(service == "google-drive", *GOOGLE_DRIVE_SECRETS)
            setVisible(service == "s3", S3_ENDPOINT, S3_REGION, S3_BUCKET, S3_PATH_STYLE, *S3_SECRETS)
            setVisible(service == "template", XDRIVE_SECRETS, XDRIVE_TEMPLATE)
            setVisible(service != "local", "$STREAM.xdrive.frontAddress", XDRIVE_FRONT_PORT)
        }
        passwordSummary(*GOOGLE_DRIVE_SECRETS, *S3_SECRETS, XDRIVE_SECRETS)
        multilineInput(XDRIVE_SECRETS)
        presetTextSummary(S3_REGION, "", "us-east-1")
        portInput(XDRIVE_FRONT_PORT)
        presetTextSummary(XDRIVE_FRONT_PORT, "", "443")
        for ((name, xrayDefault) in XDRIVE_TUNING_DEFAULTS) {
            numberInput("$STREAM.xdrive.$name")
            presetTextSummary("$STREAM.xdrive.$name", "", xrayDefault)
        }

        val tls = findPreference<PreferenceCategory>("xrayTlsCategory")
        val reality = findPreference<PreferenceCategory>("xrayRealityCategory")
        onMenu("$STREAM.security") { security ->
            tls?.isVisible = security == "tls"
            reality?.isVisible = security == "reality"
        }

        // dialog_edit_profile_xray.cpp, edit_xrayvless.cpp: a stored value the menu lacks is shown, and flagged by
        // validate, instead of being rewritten on save; a flow matching an entry but for case is canonicalised.
        findPreference<ListPreference>("$STREAM.security")?.keepUnlistedValue()
        val flowMenu = findPreference<ListPreference>("flow")
        if (flowMenu != null) {
            val flow = flowMenu.value.orEmpty().trim()
            val listed = flowMenu.entryValues.firstOrNull { it.toString().equals(flow, ignoreCase = true) }?.toString()
            if (listed != null) {
                if (listed != flowMenu.value) flowMenu.value = listed
            } else if (flow.equals("none", ignoreCase = true)) {
                flowMenu.value = ""
            } else {
                flowMenu.keepUnlistedValue()
            }
        }
    }

    /** xrayStreamSetting::Validate names the Xray JSON paths (streamSettings.tlsSettings.serverName …); rows are streamSetting.tls.*. */
    override fun issueKey(field: String): String? {
        when (field) {
            "id" -> return "uuid"
            "mux" -> return "multiplex.enabled"
            "mux.concurrency", "mux.maxConnections", "mux.xudpConcurrency", "mux.xudpProxyUDP443" ->
                return "multiplex." + field.removePrefix("mux.")
            // only XDRIVE uses the stream's own port: its front port
            "streamSettings.port" -> return XDRIVE_FRONT_PORT
            "streamSettings.xdriveSettings.secrets" -> return when (cached(XDRIVE_SERVICE)) {
                "template" -> XDRIVE_SECRETS
                "s3" -> S3_SECRETS[0]
                else -> GOOGLE_DRIVE_SECRETS[0]
            }
            "streamSettings.xdriveSettings.template" -> return XDRIVE_TEMPLATE
            "streamSettings.xdriveSettings.s3.endpoint" -> return S3_ENDPOINT
            "streamSettings.xdriveSettings.s3.region" -> return S3_REGION
            "streamSettings.xdriveSettings.s3.bucket" -> return S3_BUCKET
            "streamSettings.xdnsResolvers", "streamSettings.xdnsResolverList" -> return XDNS_RESOLVERS
        }
        // the xdns checks name paths inside the finalmask, which is edited as one JSON row
        if (field.startsWith("streamSettings.finalmask.")) return FINALMASK
        if (!field.startsWith("streamSettings.")) return null
        var rest = field.removePrefix("streamSettings.")
        val section = XRAY_SECTIONS[rest.substringBefore('.')]
        if (section != null) {
            rest = rest.substringAfter('.', "")
            if (section == "xhttp") {
                rest = rest.removePrefix("extra.").removePrefix("xmux.")
                // the download leg's address, port and network are all edited in its JSON row
                if (rest.startsWith("downloadSettings", ignoreCase = true)) rest = "downloadSettings"
            }
            rest = "$section.$rest"
        }
        val key = "$STREAM.$rest"
        return key.takeIf { k -> pbm.items.any { it.cacheName == k } }
    }

    // dialog_edit_profile_xray.cpp bindXrayStream / bindXrayXHTTP: what validate cannot see in the parsed model.
    override fun ValidationForm.bindValidation() {
        for (key in listOf("$STREAM.ws.host", "$STREAM.httpupgrade.host", "$STREAM.xhttp.host", "$STREAM.grpc.authority")) {
            bind(key) { CommonChecks.hostHeader(it, false) }.warning()
        }
        for (key in listOf("$STREAM.ws.headers", "$STREAM.httpupgrade.headers", "$STREAM.xhttp.headers")) bind(key, ::headerLines)
        for (key in listOf("$STREAM.ws.ed", "$STREAM.httpupgrade.ed")) bind(key, Validation.Rules.int(0, Int.MAX_VALUE.toLong()))
        bind("$STREAM.xhttp.scMaxBufferedPosts", Validation.Rules.int(0, Long.MAX_VALUE))
        bind("$STREAM.xhttp.hKeepAlivePeriod", Validation.Rules.int(Long.MIN_VALUE, Long.MAX_VALUE))

        // dialog_edit_profile_xdrive.cpp bindXrayXDrive, edit_xrayvless.cpp; the cross rules mark every row they read
        for ((name, _) in XrayXDrive.TUNING) bind("$STREAM.xdrive.$name", XrayChecks::xrayXDriveTuning)
        bind(XDRIVE_FRONT_PORT, XrayChecks::xrayXDriveFrontPort)
        addRule(GOOGLE_DRIVE_SECRETS.toList() + S3_SECRETS + XDRIVE_SECRETS + XDRIVE_SERVICE) {
            // only google-drive and s3 check their secrets, and neither keeps the stored ones
            val service = cached(XDRIVE_SERVICE)
            XrayChecks.xrayXDriveSecrets(service, secretsFromUi(service, emptyList()))
        }
        addRule(listOf(S3_ENDPOINT, XDRIVE_SERVICE)) { XrayChecks.xrayXDriveS3Endpoint(cached(XDRIVE_SERVICE), cached(S3_ENDPOINT)) }
        addRule(listOf(S3_REGION, XDRIVE_SERVICE)) { XrayChecks.xrayXDriveS3Region(cached(XDRIVE_SERVICE), cached(S3_REGION)) }
        addRule(listOf(S3_BUCKET, S3_PATH_STYLE, S3_ENDPOINT, XDRIVE_SERVICE)) {
            val pathStyle = DataStore.profileCacheStore.getBoolean(S3_PATH_STYLE, true)
            XrayChecks.xrayXDriveS3Bucket(cached(XDRIVE_SERVICE), cached(S3_BUCKET), cached(S3_ENDPOINT), pathStyle)
        }

        // dialog_edit_profile_kcp.cpp bindXrayKCP; ResolverSourceWidget::bindValidation
        bind("$STREAM.kcp.mtu", XrayChecks::xrayKCPMtu)
        bind("$STREAM.kcp.tti", XrayChecks::xrayKCPTti)
        bind("$STREAM.kcp.uplinkCapacity", XrayChecks::xrayKCPCapacity)
        bind("$STREAM.kcp.downlinkCapacity", XrayChecks::xrayKCPCapacity)
        bind("$STREAM.kcp.cwndMultiplier", XrayChecks::xrayKCPCwndMultiplier)
        addRule(listOf("$STREAM.kcp.maxSendingWindow", "$STREAM.kcp.mtu")) {
            XrayChecks.xrayKCPMaxSendingWindow(cached("$STREAM.kcp.maxSendingWindow"), cached("$STREAM.kcp.mtu"))
        }
        bindLines(XDNS_RESOLVERS, ResolverChecks::xdnsLine)
        addRule(listOf("encryption", "$STREAM.network")) {
            XrayVlessChecks.xrayXDriveEncryption(cached("encryption"), cached("$STREAM.network"))
        }
        XrayMuxBlock.bindValidation(this)
        addRule(listOf("multiplex.enabled", "$STREAM.network")) {
            XrayVlessChecks.xrayXDriveMux(cached("multiplex.enabled").toIntOrNull() ?: 0, cached("$STREAM.network"))
        }
    }

    companion object {
        private const val STREAM = "streamSetting"
        private const val XDRIVE_SERVICE = "$STREAM.xdrive.service"
        private const val XDRIVE_SECRETS = "$STREAM.xdrive.secrets"
        private const val XDRIVE_TEMPLATE = "$STREAM.xdrive.template"
        private const val XDRIVE_FRONT_PORT = "$STREAM.xdrive.frontPort"
        private const val S3_ENDPOINT = "$STREAM.xdrive.s3Endpoint"
        private const val S3_REGION = "$STREAM.xdrive.s3Region"
        private const val S3_BUCKET = "$STREAM.xdrive.s3Bucket"
        private const val S3_PATH_STYLE = "$STREAM.xdrive.s3PathStyle"
        private const val FINALMASK = "$STREAM.finalmask"
        private const val XDNS_RESOLVERS = "$STREAM.xdnsResolvers"

        /** No fields of their own: secrets[0..2] while the service is google-drive. */
        private val GOOGLE_DRIVE_SECRETS = arrayOf(
            "$STREAM.xdrive.clientId", "$STREAM.xdrive.clientSecret", "$STREAM.xdrive.refreshToken",
        )

        /** No fields of their own: secrets[0..2] while the service is s3; the session token is optional. */
        private val S3_SECRETS = arrayOf(
            "$STREAM.xdrive.s3AccessKey", "$STREAM.xdrive.s3SecretKey", "$STREAM.xdrive.s3SessionToken",
        )

        /** dialog_edit_profile_xdrive.cpp xdriveTuningEdits: what 0 leaves Xray at. */
        private val XDRIVE_TUNING_DEFAULTS = listOf(
            "segmentBytes" to "524288",
            "flushIntervalMs" to "20",
            "pollIntervalMs" to "50",
            "maxPollIntervalMs" to "500",
            "eagerWindowMs" to "2000",
            "holeTimeoutMs" to "30000",
            "sessionTtlSeconds" to "300",
            "concurrency" to "8",
        )

        private val XRAY_SECTIONS = mapOf(
            "tlsSettings" to "tls",
            "realitySettings" to "reality",
            "xhttpSettings" to "xhttp",
            "splithttpSettings" to "xhttp",
            "wsSettings" to "ws",
            "httpupgradeSettings" to "httpupgrade",
            "grpcSettings" to "grpc",
            "kcpSettings" to "kcp",
            "xdriveSettings" to "xdrive",
        )
    }

}
