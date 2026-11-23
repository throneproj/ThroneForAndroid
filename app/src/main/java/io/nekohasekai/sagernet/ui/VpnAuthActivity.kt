package io.nekohasekai.sagernet.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.VpnAuthClient
import io.nekohasekai.sagernet.bg.VpnAuthState
import io.nekohasekai.sagernet.databinding.LayoutVpnAuthBinding
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The sign-in prompt of an OpenVPN/OpenConnect endpoint of the running profile (the desktop's DialogVpnAuth): the
 * fields of its challenge by kind, answered or cancelled through the service, and the refusal when the server rejects
 * the sign-in. Back leaves the challenge pending; what is typed only ever goes to the service.
 */
class VpnAuthActivity : ThemedActivity(), SagerConnection.Callback {

    companion object {
        const val EXTRA_TAG = "endpointTag"

        /** How long an accepted answer waits for the endpoint to connect, fail or ask again before the prompt closes. */
        private const val ANSWER_WAIT_MS = 30_000L
        private const val SERVICE_WAIT_STEPS = 30

        private const val ANSWER_USERNAME = "username"
        private const val ANSWER_PASSWORD = "password"
        private const val ANSWER_SECRET = "secret"
        private const val ANSWER_FORM_VALUES = "form_values"

        fun intent(context: Context, tag: String): Intent =
            Intent(context, VpnAuthActivity::class.java).putExtra(EXTRA_TAG, tag)
    }

    /** One answer slot: an OpenVPN answer key or a form field's submission key, and how to read it. */
    private class Input(val key: String, val read: () -> String)

    override val isDialog = true

    private lateinit var binding: LayoutVpnAuthBinding
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_VPN_AUTH, true)

    private var fresh = false
    private var endpointTag: String? = null
    private var shown: VpnAuthState.Challenge? = null
    private var shownFailure: VpnAuthState.Failure? = null

    /** The challenge an answer was accepted for: the prompt now waits for what the endpoint does next. */
    private var answered: String? = null
    private var busy = false

    /** A cancel is on its way; what it changes is not shown any more. */
    private var closing = false
    private val inputs = ArrayList<Input>()
    private var deadlineJob: Job? = null
    private var waitJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme.applyStyle(R.style.ThemeOverlay_SagerNet_Dialog_WrapHeight, true)
        binding = LayoutVpnAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setFinishOnTouchOutside(false)
        endpointTag = intent.getStringExtra(EXTRA_TAG)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        if (tag == endpointTag || busy || answered != null) return
        endpointTag = tag
        reset()
        if (fresh) render(VpnAuthClient.state.value)
    }

    override fun onStart() {
        super.onStart()
        connection.connect(this, this)
    }

    override fun onStop() {
        fresh = false
        connection.disconnect(this)
        super.onStop()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}

    override fun onServiceConnected(service: ISagerNetService) {}

    // Only a state this screen's own connection fetched: what another screen left behind may be stale.
    override fun cbVpnAuthUpdate() {
        fresh = true
        render(VpnAuthClient.state.value)
    }

    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    // ------------------------------------------------------------------------------------------------ state

    private fun render(state: VpnAuthState?) {
        // The service has not reported yet.
        if (state == null || closing) return
        val tag = endpointTag
        val challenge = tag?.let(state::challenge)?.takeUnless { it.expired() }
        if (challenge != null) {
            if (challenge.key != answered && challenge.key != shown?.key) showChallenge(challenge)
            return
        }
        val failure = tag?.let(state::failure)
        if (failure != null) {
            if (failure != shownFailure) showFailure(failure)
            return
        }
        when {
            answered != null -> if (tag in state.connected) finish()
            shownFailure != null -> finish()
            else -> {
                val next = state.challenges.firstOrNull { !it.expired() }
                if (next != null) {
                    endpointTag = next.tag
                    reset()
                    showChallenge(next)
                } else {
                    if (shown == null) Toast.makeText(this, R.string.vpn_auth_gone, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun reset() {
        shown = null
        shownFailure = null
        answered = null
        busy = false
        inputs.clear()
        binding.fields.removeAllViews()
        deadlineJob?.cancel()
        waitJob?.cancel()
        binding.progress.visibility = View.GONE
        status(null)
    }

    private fun showChallenge(challenge: VpnAuthState.Challenge) {
        reset()
        shown = challenge
        VpnAuthClient.markPrompted(challenge)
        binding.title.setText(R.string.vpn_auth_title)
        binding.endpoint.text = challenge.name
        text(binding.error, challenge.error)
        text(binding.banner, challenge.banner)
        text(binding.message, challenge.message)
        text(binding.url, null)
        val cancel = getString(android.R.string.cancel) to { cancelChallenge(challenge) }
        when (challenge.kind) {
            VpnAuthState.KIND_CREDENTIALS -> {
                val username = textField(ANSWER_USERNAME, getString(R.string.username), challenge.username, masked = false)
                val password = textField(ANSWER_PASSWORD, getString(R.string.password), "", masked = true)
                // The core packs the answer into SCRV1 next to the password; the message is its label.
                textField(ANSWER_SECRET, challenge.message.ifEmpty { getString(R.string.vpn_auth_answer) }, "", !challenge.echo)
                text(binding.message, null)
                (if (challenge.username.isEmpty()) username else password).requestFocus()
                buttons(negative = cancel, positive = getString(R.string.vpn_auth_sign_in) to { submit(challenge) })
            }

            VpnAuthState.KIND_SECRET -> {
                textField(ANSWER_SECRET, getString(R.string.vpn_auth_answer), "", !challenge.echo).requestFocus()
                buttons(negative = cancel, positive = getString(R.string.vpn_auth_sign_in) to { submit(challenge) })
            }

            VpnAuthState.KIND_FORM -> {
                formFields(challenge)
                buttons(negative = cancel, positive = getString(R.string.vpn_auth_sign_in) to { submit(challenge) })
            }

            VpnAuthState.KIND_MESSAGE -> {
                if (challenge.message.isEmpty() && challenge.banner.isEmpty()) {
                    text(binding.message, getString(R.string.vpn_auth_server_message))
                }
                buttons(positive = getString(android.R.string.ok) to { submit(challenge) })
            }

            VpnAuthState.KIND_OPEN_URL -> {
                text(binding.url, challenge.url)
                if (challenge.message.isEmpty()) text(binding.message, getString(R.string.vpn_auth_open_url_hint))
                // The server settles this one out of band, so Done leaves it pending rather than cancelling it.
                buttons(
                    neutral = if (challenge.url.isEmpty()) null else getString(R.string.vpn_auth_open_browser) to { openUrl(challenge.url) },
                    negative = cancel,
                    positive = getString(R.string.vpn_auth_done) to { finish() },
                )
            }

            VpnAuthState.KIND_BROWSER -> {
                text(binding.url, challenge.url)
                text(binding.message, getString(R.string.vpn_auth_browser_unsupported))
                buttons(negative = cancel)
            }

            else -> {
                text(binding.url, challenge.url)
                text(binding.message, getString(R.string.vpn_auth_unsupported, challenge.kind))
                buttons(negative = cancel)
            }
        }
        if (challenge.deadline > 0) deadlineJob = lifecycleScope.launch { countDown(challenge) }
    }

    private fun showFailure(failure: VpnAuthState.Failure) {
        reset()
        shownFailure = failure
        binding.title.setText(if (failure.authFailed) R.string.vpn_auth_refused else R.string.vpn_auth_failed)
        binding.endpoint.text = failure.name
        text(binding.error, failure.error)
        text(binding.banner, null)
        text(binding.message, getString(R.string.vpn_auth_refused_hint))
        text(binding.url, null)
        buttons(
            negative = getString(R.string.vpn_auth_close) to { finish() },
            positive = getString(R.string.vpn_auth_restart) to {
                SagerNet.reloadService()
                finish()
            },
        )
    }

    private suspend fun countDown(challenge: VpnAuthState.Challenge) {
        while (true) {
            val remaining = challenge.deadline - System.currentTimeMillis() / 1000
            if (remaining <= 0) {
                // The core drops an expired challenge itself, so cancelling it would only fail.
                Toast.makeText(this, R.string.vpn_auth_expired, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            if (!busy && answered == null) {
                status(getString(R.string.vpn_auth_expires_in, if (remaining >= 60) "${remaining / 60}m ${remaining % 60}s" else "${remaining}s"))
            }
            delay(1000)
        }
    }

    // ------------------------------------------------------------------------------------------------ fields

    private fun textField(key: String, label: String, value: String, masked: Boolean): TextView {
        val layout = layoutInflater.inflate(R.layout.layout_vpn_auth_text_field, binding.fields, false) as TextInputLayout
        layout.hint = label
        val edit = layout.editText!!
        if (masked) {
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            // Kept out of the saved instance state.
            edit.isSaveEnabled = false
        }
        edit.setText(value)
        binding.fields.addView(layout)
        inputs.add(Input(key) { edit.text?.toString().orEmpty() })
        return edit
    }

    private fun formFields(challenge: VpnAuthState.Challenge) {
        var first: View? = null
        for (field in challenge.fields) {
            val label = field.label.ifEmpty { field.name }.ifEmpty { field.submissionKey }
            val view: View? = when (field.kind) {
                VpnAuthState.FIELD_HIDDEN -> {
                    inputs.add(Input(field.submissionKey) { field.value })
                    null
                }

                VpnAuthState.FIELD_SELECT -> selectField(field, label)
                else -> textField(field.submissionKey, label, field.value, field.kind == VpnAuthState.FIELD_PASSWORD)
            }
            if (first == null && view != null) first = view
        }
        first?.requestFocus()
    }

    private fun selectField(field: VpnAuthState.Field, label: String): View {
        val layout = layoutInflater.inflate(R.layout.layout_vpn_auth_select_field, binding.fields, false) as TextInputLayout
        layout.hint = label
        val dropdown = layout.editText as AutoCompleteTextView
        val labels = field.options.map { it.label.ifEmpty { it.value } }
        dropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        var selected = field.options.firstOrNull { it.value == field.value } ?: field.options.firstOrNull()
        selected?.let { dropdown.setText(it.label.ifEmpty { it.value }, false) }
        dropdown.setOnItemClickListener { _, _, position, _ -> selected = field.options.getOrNull(position) }
        binding.fields.addView(layout)
        inputs.add(Input(field.submissionKey) { selected?.value ?: field.value })
        return dropdown
    }

    private fun answerJson(challenge: VpnAuthState.Challenge): String {
        val answer = JsonObject()
        if (challenge.kind == VpnAuthState.KIND_FORM) {
            val values = JsonObject()
            for (input in inputs) values[input.key] = input.read()
            answer[ANSWER_FORM_VALUES] = values
        } else {
            for (input in inputs) answer[input.key] = input.read()
        }
        return answer.toCompact()
    }

    // ------------------------------------------------------------------------------------------------ actions

    /** The prompt can show a state another screen fetched before its own connection is up. */
    private suspend fun awaitService(): ISagerNetService? {
        repeat(SERVICE_WAIT_STEPS) {
            connection.service?.let { return it }
            delay(100)
        }
        return connection.service
    }

    private fun submit(challenge: VpnAuthState.Challenge) {
        if (busy) return
        val answer = answerJson(challenge)
        setBusy(true, getString(R.string.vpn_auth_submitting))
        lifecycleScope.launch {
            val service = awaitService() ?: return@launch notRunning()
            val error = withContext(Dispatchers.IO) {
                try {
                    service.submitVpnChallenge(challenge.tag, challenge.id, answer).orEmpty()
                } catch (e: Exception) {
                    e.readableMessage
                }
            }
            if (shown?.key != challenge.key) return@launch
            if (error.isNotEmpty()) {
                setBusy(false, error)
                return@launch
            }
            if (challenge.kind == VpnAuthState.KIND_MESSAGE) return@launch finish()
            answered = challenge.key
            deadlineJob?.cancel()
            waitJob = lifecycleScope.launch {
                delay(ANSWER_WAIT_MS)
                finish()
            }
            buttons(negative = getString(R.string.vpn_auth_close) to { finish() })
            render(VpnAuthClient.state.value)
        }
    }

    private fun cancelChallenge(challenge: VpnAuthState.Challenge) {
        if (busy) return
        setBusy(true, null)
        closing = true
        lifecycleScope.launch {
            val service = awaitService()
            if (service != null) withContext(Dispatchers.IO) {
                runCatching { service.cancelVpnChallenge(challenge.tag, challenge.id) }
            }
            finish()
        }
    }

    private fun notRunning() {
        Toast.makeText(this, R.string.vpn_auth_gone, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.vpn_auth_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------------------------------------ views

    private fun setBusy(value: Boolean, statusText: String?) {
        busy = value
        binding.fields.isEnabled = !value
        for (i in 0 until binding.fields.childCount) binding.fields.getChildAt(i).isEnabled = !value
        binding.positive.isEnabled = !value
        binding.negative.isEnabled = !value
        binding.neutral.isEnabled = !value
        binding.progress.visibility = if (value) View.VISIBLE else View.GONE
        status(statusText)
    }

    private fun status(value: String?) = text(binding.status, value)

    private fun text(view: TextView, value: String?) {
        view.text = value.orEmpty()
        view.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun buttons(
        neutral: Pair<String, () -> Unit>? = null,
        negative: Pair<String, () -> Unit>? = null,
        positive: Pair<String, () -> Unit>? = null,
    ) {
        fun Button.bind(action: Pair<String, () -> Unit>?) {
            visibility = if (action == null) View.GONE else View.VISIBLE
            isEnabled = true
            text = action?.first
            setOnClickListener { action?.second?.invoke() }
        }
        binding.neutral.bind(neutral)
        binding.negative.bind(negative)
        binding.positive.bind(positive)
    }
}
