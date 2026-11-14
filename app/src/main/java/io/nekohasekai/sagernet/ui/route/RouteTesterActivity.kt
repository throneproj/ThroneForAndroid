package io.nekohasekai.sagernet.ui.route

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.CoreServiceClient
import io.nekohasekai.sagernet.bg.RouteTester
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.databinding.LayoutRouteTesterBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteTesterStepBinding
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.config.RouteRuleOrigin
import io.nekohasekai.sagernet.outbound.config.RouteRuleOriginKind
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.ui.AppListActivity
import io.nekohasekai.sagernet.ui.PackageNameInput
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The desktop's rule tester (DialogRuleTester, "Route path lookup"): walks the running config's route rules, or those
 * of a routing profile's config built offline, for one made-up connection, and names each rule through the origins
 * the generator recorded. [EXTRA_ROUTE_ID] preselects a routing profile, [EXTRA_ROUTE_DRAFT] (RouteJson) an unsaved
 * one from the editor.
 */
class RouteTesterActivity : ThemedActivity() {

    companion object {
        const val EXTRA_ROUTE_ID = "routeId"
        const val EXTRA_ROUTE_DRAFT = "routeDraft"

        private const val TARGET_RUNNING = -1L
        private const val TARGET_DRAFT = -2L

        private const val PROTOCOL_AUTO = "auto"
        private const val PROTOCOL_NONE = "none"
        private val SNIFFERS = listOf(
            "tls" to "TLS", "http" to "HTTP", "quic" to "QUIC", "dns" to "DNS", "stun" to "STUN",
            "bittorrent" to "BitTorrent", "dtls" to "DTLS", "ssh" to "SSH", "rdp" to "RDP", "ntp" to "NTP",
        )
    }

    internal sealed interface TestState {
        object Idle : TestState
        object Busy : TestState

        /** The running config went away; [routeId] is the routing profile it ran with. */
        class NotRunning(val routeId: Long) : TestState
        class Done(val reply: JsonObject) : TestState
    }

    class TesterModel : ViewModel() {
        /** The selected target: a routing profile id, [TARGET_RUNNING] or [TARGET_DRAFT]; null until the first list. */
        var targetId: Long? = null
        var protocol = PROTOCOL_AUTO
        internal val state = MutableStateFlow<TestState>(TestState.Idle)

        fun run(request: JsonObject, running: Boolean) {
            state.value = TestState.Busy
            viewModelScope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        CoreServiceClient.call { it.testRoute(request.toCompact()) }
                    } catch (e: Exception) {
                        jsonObjectOf("error" to e.readableMessage).toCompact()
                    }
                }
                val reply = JsonInput.parseObject(text)
                state.value = if (running && reply.bool("not_running")) {
                    TestState.NotRunning(DataStore.runningRouteId)
                } else {
                    TestState.Done(reply)
                }
            }
        }
    }

    private class Target(val id: Long, val label: String) {
        override fun toString() = label
    }

    /** RuleNames of the dialog: what a reply's rule indexes and outbound tags stand for. */
    private class RuleNames(val origins: List<RouteRuleOrigin>, val outbounds: Map<String, String>, val builtWith: String)

    private val model: TesterModel by viewModels()
    private lateinit var binding: LayoutRouteTesterBinding
    private var targets: List<Target> = emptyList()
    private var routeNames: Map<Long, String> = emptyMap()
    private var draftName: String? = null

    private val appPicker = registerForActivityResult(AppListActivity.Contract()) { list ->
        // The picker selects several; the most recently ticked app wins, none clears the field.
        if (list != null) binding.app.setText(list.lastOrNull().orEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutRouteTesterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.root.findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_tester)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }
        binding.scroll.applyListInsets(ime = true)
        draftName = intent.getStringExtra(EXTRA_ROUTE_DRAFT)?.let { runCatching { RouteJson.profileFromJson(it).name }.getOrNull() }

        binding.networkTcp.text = "TCP"
        binding.networkUdp.text = "UDP"
        if (savedInstanceState == null) binding.port.setText("443")
        binding.appLayout.placeholderText = "com.example.app"
        binding.app.doAfterTextChanged { showAppLabel() }
        binding.appLayout.setEndIconOnClickListener {
            val current = binding.app.text?.toString()?.trim().orEmpty()
            appPicker.launch(if (PackageNameInput.isPackageName(current)) listOf(current) else emptyList())
        }
        setupProtocols()
        binding.target.setOnItemClickListener { _, _, position, _ ->
            model.targetId = targets.getOrNull(position)?.id
            updateHeader()
        }
        binding.test.setOnClickListener { runTest() }

        lifecycleScope.launch { model.state.collect { render(it) } }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        // The service may have started or stopped meanwhile; the desktop follows statusChanged.
        refreshTargets()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)

    private fun setupProtocols() {
        val values = listOf(PROTOCOL_AUTO, PROTOCOL_NONE) + SNIFFERS.map { it.first }
        val labels = listOf(getString(R.string.route_tester_protocol_auto), getString(R.string.route_tester_protocol_none)) +
            SNIFFERS.map { it.second }
        binding.protocol.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        binding.protocol.setText(labels[values.indexOf(model.protocol).coerceAtLeast(0)], false)
        binding.protocol.setOnItemClickListener { _, _, position, _ -> model.protocol = values[position] }
    }

    private fun showAppLabel() {
        val name = binding.app.text?.toString()?.trim().orEmpty()
        val label = if (PackageNameInput.isPackageName(name)) {
            runCatching { packageManager.getApplicationInfo(name, 0).loadLabel(packageManager).toString() }.getOrNull()
        } else {
            null
        }
        binding.appLayout.helperText = label
        binding.appLayout.error = null
    }

    /** fillRouteCombo plus the running entry while connected; keeps the selection when it is still listed. */
    private fun refreshTargets(select: Long? = null, thenTest: Boolean = false) {
        lifecycleScope.launch {
            val routes = withContext(Dispatchers.IO) { RouteManager.usable() }
            val list = ArrayList<Target>()
            if (DataStore.serviceState.connected) list.add(Target(TARGET_RUNNING, getString(R.string.route_tester_target_running)))
            draftName?.let { list.add(Target(TARGET_DRAFT, getString(R.string.route_tester_draft, it))) }
            for (route in routes) list.add(Target(route.id, route.name))
            targets = list
            routeNames = routes.associate { it.id to it.name }
            val wanted = select ?: model.targetId ?: initialTarget()
            val chosen = list.firstOrNull { it.id == wanted }
                ?: list.firstOrNull { it.id == DataStore.currentRouteId }
                ?: list.firstOrNull { it.id != TARGET_RUNNING }
            model.targetId = chosen?.id
            binding.target.setAdapter(ArrayAdapter(this@RouteTesterActivity, android.R.layout.simple_list_item_1, list))
            binding.target.setText(chosen?.label.orEmpty(), false)
            updateHeader()
            if (thenTest) runTest()
        }
    }

    private fun initialTarget(): Long = when {
        draftName != null -> TARGET_DRAFT
        intent.getLongExtra(EXTRA_ROUTE_ID, 0L) > 0L -> intent.getLongExtra(EXTRA_ROUTE_ID, 0L)
        DataStore.serviceState.connected -> TARGET_RUNNING
        else -> DataStore.currentRouteId
    }

    private fun updateHeader() {
        val running = model.targetId == TARGET_RUNNING
        binding.scope.isVisible = running
        binding.offlineHint.isVisible = !running
        if (!running) return
        val routeId = RouteManager.runningRouteId()
        val name = routeNames[routeId]
        binding.scope.text = when {
            name != null -> getString(R.string.route_tester_running, name)
            routeId == 0L -> getString(R.string.route_tester_running_custom)
            else -> getString(R.string.route_tester_running_plain)
        }
    }

    // ------------------------------------------------------------------------------------------------ request

    /** buildRequest: the fields trimmed, a pasted URL cut to its host, the domain in ACE form; null after showing why not. */
    private fun buildRequest(): JsonObject? {
        binding.appLayout.error = null
        binding.domainLayout.error = null
        binding.ipLayout.error = null
        binding.portLayout.error = null
        val app = binding.app.text?.toString()?.trim().orEmpty()
        var domain = binding.domain.text?.toString()?.trim().orEmpty()
        if (domain.contains("://")) domain = Uri.parse(domain).host.orEmpty()
        domain = domain.trimEnd('.')
        var ip = binding.ip.text?.toString()?.trim().orEmpty()
        if (ip.startsWith("[") && ip.endsWith("]")) ip = ip.substring(1, ip.length - 1)

        if (app.isEmpty() && domain.isEmpty() && ip.isEmpty()) {
            showMessage(getString(R.string.route_tester_need_input))
            return null
        }
        if (app.isNotEmpty() && !PackageNameInput.isPackageName(app)) {
            binding.appLayout.error = getString(R.string.picker_invalid_package, app)
            return null
        }
        if (domain.isNotEmpty()) {
            if (Hosts.isIpAddress(domain)) {
                binding.domainLayout.error = getString(R.string.route_tester_domain_is_ip, domain)
                return null
            }
            val ace = Hosts.toAce(domain)
            if (ace.isEmpty()) {
                binding.domainLayout.error = getString(R.string.route_tester_bad_domain, domain)
                return null
            }
            domain = ace
        }
        if (ip.isNotEmpty() && !Hosts.isIpAddress(ip)) {
            binding.ipLayout.error = getString(R.string.route_tester_bad_ip, ip)
            return null
        }
        val port = binding.port.text?.toString()?.trim()?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            binding.portLayout.error = getString(R.string.validation_port)
            return null
        }
        return jsonObjectOf(
            "package_name" to app,
            "domain" to domain,
            "ip" to ip,
            "port" to port,
            "network" to if (binding.networkUdp.isChecked) "udp" else "tcp",
            "protocol" to model.protocol,
        )
    }

    private fun runTest() {
        if (model.state.value == TestState.Busy) return
        val request = buildRequest() ?: return
        when (val target = model.targetId) {
            null -> showMessage(getString(R.string.route_tester_select_route))
            TARGET_RUNNING -> {
                request["running"] = true
                model.run(request, running = true)
            }

            TARGET_DRAFT -> {
                request["route_json"] = intent.getStringExtra(EXTRA_ROUTE_DRAFT).orEmpty()
                model.run(request, running = false)
            }

            else -> {
                request["route_id"] = target
                model.run(request, running = false)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ result

    private fun render(state: TestState) {
        val busy = state == TestState.Busy
        binding.test.isEnabled = !busy
        binding.progress.isVisible = busy
        when (state) {
            TestState.Idle -> Unit
            TestState.Busy -> showMessage(getString(R.string.connection_test_testing))
            // finish(): the running config went away, so the test moves to the routing profile it ran with.
            is TestState.NotRunning -> {
                model.state.value = TestState.Idle
                refreshTargets(select = state.routeId.takeIf { it > 0L } ?: DataStore.currentRouteId, thenTest = true)
            }

            is TestState.Done -> showReply(state.reply)
        }
    }

    private fun showMessage(text: String) {
        binding.resultCard.isVisible = true
        binding.headline.text = text
        binding.summary.isVisible = false
        binding.skipped.isVisible = false
        binding.stepsTitle.isVisible = false
        binding.steps.removeAllViews()
    }

    private fun showReply(reply: JsonObject) {
        val code = reply.string("error_code")
        if (code.isNotEmpty()) {
            val error = when (code) {
                RouteTester.ERROR_ROUTE_GONE -> getString(R.string.route_tester_route_gone)
                RouteTester.ERROR_NO_PROFILE -> getString(R.string.route_tester_no_profile)
                else -> reply.string("error")
            }
            showMessage(getString(R.string.route_tester_build_failed, error))
            return
        }
        val result = reply.obj("result")
        val failure = reply.string("error").ifEmpty { result.string("error") }
        if (failure.isNotEmpty()) {
            showMessage(getString(R.string.route_tester_failed, failure))
            return
        }
        val origins = reply.array("origins").mapNotNull { item ->
            val origin = item as? JsonObject ?: return@mapNotNull null
            RouteRuleOrigin(
                RouteRuleOriginKind.ofId(origin.string("kind")) ?: RouteRuleOriginKind.PROFILE_RULE,
                origin.int("rule"),
                origin.string("name"),
            )
        }
        val outbounds = reply.obj("outbounds").associate { it.key to (it.value as? String).orEmpty() }
        showResult(result, RuleNames(origins, outbounds, reply.string("built_with")))
    }

    private fun showResult(result: JsonObject, names: RuleNames) {
        val steps = result.array("steps").mapNotNull { it as? JsonObject }
        val action = result.string("action")
        val isFinal = result.bool("is_final")
        val deciding = if (!isFinal) steps.lastOrNull() else null
        val decidingLabel = deciding?.let { ruleLabel(stepIndex(it), it.string("rule"), names) }.orEmpty()

        binding.resultCard.isVisible = true
        binding.headline.text = when (action) {
            "reject" -> if (decidingLabel.isEmpty()) getString(R.string.route_tester_rejected)
            else getString(R.string.route_tester_rejected_by, decidingLabel)

            "hijack-dns" -> if (decidingLabel.isEmpty()) getString(R.string.route_tester_dns)
            else getString(R.string.route_tester_dns_by, decidingLabel)

            "error" -> getString(
                R.string.route_tester_closed,
                steps.lastOrNull()?.string("error").orEmpty().ifEmpty { getString(R.string.route_tester_unknown_error) },
            )

            else -> {
                val outbound = (listOf(result.string("outbound")) + result.array("outbound_chain").strings())
                    .joinToString(" → ") { outboundLabel(it, names) }
                val decision = if (isFinal || decidingLabel.isEmpty()) getString(R.string.route_tester_default)
                else getString(R.string.route_tester_matched, decidingLabel)
                outbound + "\n" + decision
            }
        }

        val summary = ArrayList<String>()
        result.string("inbound").takeIf { it.isNotEmpty() }?.let { summary.add(getString(R.string.route_tester_inbound, it)) }
        // An app-only test has no address, which sing-box formats as ":port".
        result.string("destination").takeIf { it.isNotEmpty() && !it.startsWith(':') }?.let {
            summary.add(getString(R.string.route_tester_destination, it))
        }
        result.string("domain").takeIf { it.isNotEmpty() }?.let { summary.add(getString(R.string.route_tester_domain, it)) }
        result.array("addresses").strings().takeIf { it.isNotEmpty() }?.let {
            summary.add(getString(R.string.route_tester_addresses, it.joinToString(", ")))
        }
        if (names.builtWith.isNotEmpty()) summary.add(getString(R.string.route_tester_built_with, names.builtWith))
        binding.summary.text = summary.joinToString("\n")
        binding.summary.isVisible = summary.isNotEmpty()

        val skipped = result.array("skipped").map { (it as? Number)?.toInt() ?: -1 }
        binding.skipped.isVisible = skipped.isNotEmpty()
        binding.skipped.text = if (skipped.isEmpty()) "" else {
            getString(R.string.route_tester_skipped, skipped.joinToString(", ") { ruleLabel(it, "", names) })
        }

        binding.steps.removeAllViews()
        binding.stepsTitle.isVisible = steps.isNotEmpty()
        for (step in steps) {
            val item = LayoutRouteTesterStepBinding.inflate(layoutInflater, binding.steps, false)
            val index = stepIndex(step)
            val ruleText = step.string("rule")
            item.rule.text = ruleLabel(index, ruleText, names)
            item.rule.setTypeface(null, if (step === deciding) Typeface.BOLD else Typeface.NORMAL)
            item.action.text = actionText(step, names)
            val details = detailsText(step)
            item.details.text = details
            item.details.isVisible = details.isNotEmpty()
            val raw = if (ruleText.isEmpty()) "route.rules[$index]" else "route.rules[$index]: $ruleText"
            val detail = step.string("action_detail")
            item.raw.text = if (detail.isEmpty()) raw else "$raw\n$detail"
            // The desktop's tooltips: the rule as sing-box prints it, expanded on tap.
            item.root.setOnClickListener {
                item.raw.maxLines = if (item.raw.maxLines == Int.MAX_VALUE) 2 else Int.MAX_VALUE
            }
            binding.steps.addView(item.root)
        }
    }

    private fun stepIndex(step: JsonObject): Int = if (step.contains("index")) step.int("index") else -1

    private fun outboundLabel(tag: String, names: RuleNames): String = names.outbounds[tag]?.takeIf { it.isNotEmpty() } ?: tag

    private fun originLabel(origin: RouteRuleOrigin): String = when (origin.kind) {
        RouteRuleOriginKind.PROFILE_RULE -> if (origin.name.isEmpty()) getString(R.string.route_tester_rule, origin.profileRule + 1)
        else getString(R.string.route_tester_rule_named, origin.name)

        RouteRuleOriginKind.XRAY_BRIDGE -> getString(R.string.route_tester_xray_bridge)
        RouteRuleOriginKind.SNIFF -> getString(R.string.route_tester_sniff)
        RouteRuleOriginKind.RESOLVE -> getString(R.string.route_tester_resolve)
        RouteRuleOriginKind.DNS_HIJACK -> getString(R.string.route_tester_dns_hijack)
        RouteRuleOriginKind.DNS_INBOUND_REJECT -> getString(R.string.route_tester_dns_in_guard)
        RouteRuleOriginKind.AD_BLOCK -> getString(R.string.route_tester_adblock)
        RouteRuleOriginKind.BLOCK_FINAL -> getString(R.string.route_tester_block_final)
    }

    private fun ruleLabel(index: Int, ruleText: String, names: RuleNames): String {
        if (index >= 0 && index < names.origins.size) return originLabel(names.origins[index])
        return ruleText.ifEmpty { "route.rules[$index]" }
    }

    private fun actionText(step: JsonObject, names: RuleNames): String {
        val action = step.string("action")
        val detail = step.string("action_detail")
        return when (action) {
            "route", "bypass" -> {
                val tag = actionOutbound(action, detail)
                when {
                    tag.isEmpty() -> action
                    action == "route" -> getString(R.string.route_tester_action_route_to, outboundLabel(tag, names))
                    else -> getString(R.string.route_tester_action_bypass_to, outboundLabel(tag, names))
                }
            }

            "route-options" -> getString(R.string.route_tester_action_route_options)
            "reject" -> getString(R.string.route_tester_action_reject)
            "hijack-dns" -> getString(R.string.route_tester_action_hijack_dns)
            "sniff" -> getString(R.string.route_tester_action_sniff)
            "resolve" -> getString(R.string.route_tester_action_resolve)
            else -> detail.ifEmpty { action }
        }
    }

    /** sing-box describes a route action as "route(<outbound>[,option...])". */
    private fun actionOutbound(action: String, detail: String): String {
        val prefix = "$action("
        if (!detail.startsWith(prefix) || !detail.endsWith(")")) return ""
        return detail.substring(prefix.length, detail.length - 1).substringBefore(',').trim()
    }

    private fun detailsText(step: JsonObject): String {
        val lines = ArrayList<String>()
        step.string("matched").takeIf { it.isNotEmpty() }?.let { lines.add(getString(R.string.route_tester_matched, it)) }
        if (step.string("action") == "sniff") {
            val protocol = step.string("protocol")
            val domain = step.string("sniffed_domain")
            lines.add(
                when {
                    protocol.isEmpty() -> getString(R.string.route_tester_found_nothing)
                    domain.isEmpty() -> getString(R.string.route_tester_found_protocol, protocol)
                    else -> getString(R.string.route_tester_found_domain, protocol, domain)
                }
            )
        }
        step.array("addresses").strings().takeIf { it.isNotEmpty() }?.let {
            lines.add(getString(R.string.route_tester_resolved, it.joinToString(", ")))
        }
        step.string("error").takeIf { it.isNotEmpty() }?.let { lines.add(getString(R.string.route_tester_error, it)) }
        step.string("note").takeIf { it.isNotEmpty() }?.let { lines.add(it) }
        return lines.joinToString("\n")
    }
}
