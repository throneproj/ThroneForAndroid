package io.nekohasekai.sagernet.ui.warp

import android.content.Context
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The desktop "Generate" buttons (dialog_manage_routes.cpp:460-503, edit_wireguard.cpp:19-58, edit_masque.cpp:63-90). */
object WarpGenerate {

    /** ConfirmWarpTerms (warp.cpp:56-72): asked once, the answer persisted as warp_tos_accepted. */
    fun confirmTerms(context: Context, onAccepted: () -> Unit) {
        if (DataStore.warpTosAccepted) return onAccepted()
        val message = HtmlCompat.fromHtml(
            context.getString(R.string.warp_tos_message, WarpClient.TERMS_URL), HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.cag_warp)
            .setMessage(message)
            .setPositiveButton(R.string.yes) { _, _ ->
                DataStore.warpTosAccepted = true
                onAccepted()
            }
            .setNegativeButton(R.string.no, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    }

    /**
     * After the terms, registers a [tunnelType] identity off the main thread while [button] is disabled and says
     * "Generating…"; [onIdentity] runs on the main thread, a failure shows [failedTitle] with the error. With
     * [onPackets] the amnezia packets are generated next and handed over before [onIdentity]; when they fail the
     * identity is still filled, then the error is shown the same way.
     */
    fun generate(
        owner: LifecycleOwner,
        context: Context,
        button: Preference,
        tunnelType: String,
        @StringRes failedTitle: Int,
        onPackets: ((Result<List<String>>) -> Unit)? = null,
        onIdentity: (WarpClient.Identity) -> Unit,
    ) = confirmTerms(context) {
        val idle = button.summary
        button.isEnabled = false
        button.summary = context.getString(R.string.generating)
        fun fail(e: Throwable) {
            button.summary = idle
            button.isEnabled = true
            MaterialAlertDialogBuilder(context)
                .setTitle(failedTitle)
                .setMessage(e.readableMessage)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        owner.lifecycleScope.launch {
            val identity = try {
                WarpClient.register(tunnelType)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
                return@launch
            }
            val packets = onPackets?.let { handOver -> WarpClient.generateCamouflage().also(handOver) }
            onIdentity(identity)
            packets?.exceptionOrNull()?.let {
                fail(it)
                return@launch
            }
            button.summary = context.getString(R.string.warp_generate_success)
            delay(2000)
            button.summary = idle
            button.isEnabled = true
        }
    }

    /**
     * Wires the "Generate WARP identity" row of a profile editor. [fill] gets the editor's current screen, which an
     * "Edit as JSON" round trip may have rebuilt while the registration ran; a profile without a name gets [name].
     */
    fun bindEditorRow(
        activity: AppCompatActivity,
        screen: PreferenceFragmentCompat,
        tunnelType: String,
        @StringRes name: Int,
        fill: PreferenceFragmentCompat.(WarpClient.Identity) -> Unit,
    ) {
        screen.findPreference<Preference>(KEY_EDITOR_ROW)?.setOnPreferenceClickListener { button ->
            generate(activity, activity, button, tunnelType, R.string.warp_identity_failed) { identity ->
                val current = activity.supportFragmentManager.findFragmentById(R.id.settings)
                val target = (current as? PreferenceFragmentCompat) ?: screen
                target.fill(identity)
                target.findPreference<EditTextPreference>(KEY_NAME)?.let {
                    if (it.text.isNullOrBlank()) it.text = activity.getString(name)
                }
            }
            true
        }
    }

    private const val KEY_EDITOR_ROW = "warpGenerate"
    private const val KEY_NAME = "name"
}

/** Sets a field through its preference, which writes the backing store and marks a profile editor dirty. */
fun PreferenceFragmentCompat.setFieldText(key: String, value: String) {
    findPreference<EditTextPreference>(key)?.text = value
}
