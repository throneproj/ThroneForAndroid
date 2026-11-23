package io.nekohasekai.sagernet.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.databinding.LayoutRouteRemotePromptBinding
import io.nekohasekai.sagernet.databinding.LayoutSubscribeChoiceBinding
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.ui.route.RouteImports
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * The desktop's import entry points (mainwindow_deeplink.cpp): pasted, scanned or shared text
 * (import_or_handle_deeplink, import_text), the throne:// deep links and the "Add this subscription?" prompt.
 * Imports go to group [gid] of each call, the current group when it is negative; an archived or subscription group
 * takes none (importTargetAccepted).
 */
object SubscribeFlows {

    /** A subscription to add: the URL and the group name the link suggests (may be empty). */
    class SubscribeLink(val url: String, val name: String)

    private const val MAX_IMPORT_FILE_SIZE = 50L * 1024 * 1024

    /** Text from the clipboard, a scan or a share: http(s) URL → the desktop's 3-way choice, links/documents → import into [gid] (current group when negative). */
    fun importText(activity: FragmentActivity, text: String, gid: Long = -1L) {
        val content = text.trim()
        if (content.isEmpty()) return
        if (content.startsWith("throne://")) {
            openDeepLink(activity, content, gid)
            return
        }
        subscribeLink(content)?.let {
            addSubscription(activity, it.url, it.name)
            return
        }
        if (content.startsWith("http://") || content.startsWith("https://")) {
            chooseUrlImport(activity, content, gid)
            return
        }
        importInto(activity, content, gid)
    }

    /** Several payloads at once (importFromFiles): each URL or deep link on its own, the rest as one import. */
    fun importTexts(activity: FragmentActivity, payloads: List<String>, gid: Long = -1L) {
        val batch = ArrayList<String>()
        for (payload in payloads.map { it.trim() }.filter { it.isNotEmpty() }) {
            val single = payload.startsWith("http://") || payload.startsWith("https://") ||
                payload.startsWith("throne://") || subscribeLink(payload) != null
            if (single) importText(activity, payload, gid) else batch.add(payload)
        }
        if (batch.isNotEmpty()) importInto(activity, batch.joinToString("\n"), gid)
    }

    /** "Add this subscription?" with the "Auto update" checkbox (throne://addsub, clash://install-config). */
    fun addSubscription(activity: FragmentActivity, url: String, name: String) {
        val link = url.trim()
        if (link.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.grp_add_subscription)
                .setMessage(R.string.grp_no_subscription_url)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val groupName = name.trim().ifEmpty { hostOf(link) }
        val binding = LayoutRouteRemotePromptBinding.inflate(activity.layoutInflater)
        binding.message.text = activity.getString(R.string.grp_add_subscription_prompt, groupName, link)
        binding.autoUpdate.setText(R.string.grp_auto_update)
        binding.autoUpdate.isChecked = true
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.grp_add_subscription)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                subscribe(activity, link, groupName, binding.autoUpdate.isChecked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * throne://addsub/<base64 of "url#name"> (handle_deeplink_impl) or clash://install-config?url=&name=;
     * null for any other text. An addsub link whose payload does not decode is null too (the desktop ignores it).
     */
    fun subscribeLink(text: String): SubscribeLink? {
        val content = text.trim()
        if (content.contains('\n')) return null
        if (content.startsWith("clash://install-config", ignoreCase = true)) {
            val uri = Uri.parse(content)
            return SubscribeLink(uri.getQueryParameter("url").orEmpty(), uri.getQueryParameter("name").orEmpty())
        }
        if (!content.startsWith("throne://")) return null
        val uri = Uri.parse(content)
        if (!uri.host.equals("addsub", ignoreCase = true)) return null
        val path = uri.path ?: return null
        if (!path.startsWith('/')) return null
        val data = Base64Strict.decode(path.substring(1))?.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)
            ?: return null
        val hash = data.indexOf('#')
        if (hash < 0) return SubscribeLink(data.trim(), "")
        return SubscribeLink(data.substring(0, hash).trim(), Uri.decode(data.substring(hash + 1)))
    }

    /** A file opened or shared from outside that is not a backup: its text is imported like pasted text (importFromFiles). */
    fun importUri(activity: FragmentActivity, uri: Uri, gid: Long = -1L) {
        runOnDefaultDispatcher {
            val name = displayName(uri)
            val text: String? = try {
                readText(uri, name)
            } catch (e: Exception) {
                Logs.w(e)
                val message = (e as? ImportFileException)?.message ?: activity.getString(R.string.grp_file_cannot_open, name)
                onMainDispatcher { notify(activity, message) }
                return@runOnDefaultDispatcher
            }
            onMainDispatcher {
                if (text.isNullOrBlank()) {
                    notify(activity, activity.getString(R.string.grp_file_not_readable, name))
                } else if (!activity.isFinishing && !activity.isDestroyed) {
                    importText(activity, text, gid)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ flows

    /** handle_deeplink_impl: add → import, route/remoteroute → the route import, addsub → subscribe. */
    private fun openDeepLink(activity: FragmentActivity, link: String, gid: Long) {
        val command = Uri.parse(link).host.orEmpty()
        when {
            command.equals("add", ignoreCase = true) -> importInto(activity, link, gid)
            RouteImports.isRouteLink(link) -> importRoute(activity, link)
            command.equals("addsub", ignoreCase = true) -> {
                val sub = subscribeLink(link) ?: return
                addSubscription(activity, sub.url, sub.name)
            }

            else -> {
                val message = activity.getString(R.string.grp_unknown_deeplink, command)
                Logs.w(message)
                notify(activity, message)
            }
        }
    }

    private fun importRoute(activity: FragmentActivity, link: String) {
        if (activity is MainActivity) {
            activity.importRouteLink(link)
        } else if (activity is AppCompatActivity) {
            RouteImports.importLink(activity, link) {}
        }
    }

    /** import_text: "url detected" → ImportUrl into the group / SubscribeUrl / the URL as an HTTP proxy link. */
    private fun chooseUrlImport(activity: FragmentActivity, url: String, gid: Long) {
        val binding = LayoutSubscribeChoiceBinding.inflate(activity.layoutInflater)
        binding.message.text = activity.getString(R.string.grp_how_to_update, url)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.grp_url_detected)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        binding.addProfiles.setOnClickListener {
            dialog.dismiss()
            runOnDefaultDispatcher {
                val refusal = SubscriptionClient.importRefusal(gid)
                if (refusal != null) onMainDispatcher { notify(activity, refusal) } else SubscriptionClient.importUrl(url, gid)
            }
        }
        binding.newGroup.setOnClickListener {
            dialog.dismiss()
            subscribe(activity, url, hostOf(url), true)
        }
        binding.httpProxy.setOnClickListener {
            dialog.dismiss()
            importInto(activity, url, gid)
        }
    }

    /** SubscribeUrl, then SubscriptionNewGroup(gid): the new group is focused on creation, not when its download ends. */
    private fun subscribe(activity: FragmentActivity, url: String, name: String, autoUpdate: Boolean) {
        runOnDefaultDispatcher {
            try {
                val gid = SubscriptionClient.subscribeUrl(url, name, autoUpdate)
                if (gid > 0) {
                    GroupRepo.setCurrent(gid)
                    onMainDispatcher { (activity as? MainActivity)?.showGroup(gid) }
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher { notify(activity, e.readableMessage) }
            }
        }
    }

    /**
     * ImportText into the group, then "Imported N profile(s)" unless MainActivity's report popup already said so, or
     * why the group takes no imports.
     */
    private fun importInto(activity: FragmentActivity, content: String, gid: Long) {
        runOnDefaultDispatcher {
            val message = try {
                val result = SubscriptionClient.importText(content, gid)
                if (result.reported && activity is MainActivity) return@runOnDefaultDispatcher
                result.refused ?: activity.getString(R.string.grp_imported_count, result.count)
            } catch (e: Exception) {
                Logs.w(e)
                e.readableMessage
            }
            onMainDispatcher { notify(activity, message) }
        }
    }

    private fun notify(activity: FragmentActivity, text: CharSequence) {
        if (activity.isFinishing || activity.isDestroyed) {
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        } else if (activity is MainActivity) {
            activity.snackbar(text).show()
        } else {
            Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun hostOf(url: String): String = Uri.parse(url).host.orEmpty()

    // ------------------------------------------------------------------------------------------------ files

    private class ImportFileException(message: String) : Exception(message)

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return name ?: uri.lastPathSegment ?: uri.toString()
    }

    /** The file's text, or null when it is not text (decodeImportedText); throws when it cannot be read. */
    private fun readText(uri: Uri, name: String): String? {
        val bytes = app.contentResolver.openInputStream(uri)!!.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_FILE_SIZE) throw ImportFileException(app.getString(R.string.grp_file_too_large, name))
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        return decodeImportedText(bytes)
    }

    /** A BOM names the encoding (and is dropped); otherwise text with NULs or many control bytes is not a config. */
    private fun decodeImportedText(bytes: ByteArray): String? {
        fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 0xFF) == prefix[it] }
        val bom = when {
            startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
            startsWith(0xFF, 0xFE, 0x00, 0x00) -> Charset.forName("UTF-32LE") to 4
            startsWith(0x00, 0x00, 0xFE, 0xFF) -> Charset.forName("UTF-32BE") to 4
            startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
            startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
            else -> null
        }
        if (bom != null) return String(bytes, bom.second, bytes.size - bom.second, bom.first)
        val sample = minOf(bytes.size, 8192)
        var control = 0
        for (i in 0 until sample) {
            val c = bytes[i].toInt() and 0xFF
            if (c == 0) return null
            if (c < 0x20 && c != '\t'.code && c != '\n'.code && c != '\r'.code && c != 0x0B && c != 0x0C && c != 0x1B) control++
        }
        if (control * 20 > sample) return null
        return String(bytes, Charsets.UTF_8)
    }
}
