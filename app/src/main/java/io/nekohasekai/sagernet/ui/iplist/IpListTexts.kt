package io.nekohasekai.sagernet.ui.iplist

import android.content.Context
import android.net.Uri
import android.text.InputType
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.scan.IpList
import java.text.NumberFormat

/** The texts of IpListItem.cpp and dialog_edit_ip_list.cpp, shared by the list screen and the editor. */
object IpListTexts {

    /** kEditIpListEditableEntries: longer lists open read-only. */
    const val MAX_EDITABLE = 50_000
    const val MAX_INTERVAL = 525_600
    const val DEFAULT_INTERVAL = 1440

    fun number(n: Int): String = NumberFormat.getIntegerInstance().format(n)

    fun entries(context: Context, n: Int): String = context.resources.getQuantityString(R.plurals.iplist_entries, n, number(n))

    fun interval(context: Context, minutes: Int): String = when {
        minutes % 1440 == 0 -> context.getString(R.string.iplist_interval_days, minutes / 1440)
        minutes % 60 == 0 -> context.getString(R.string.iplist_interval_hours, minutes / 60)
        else -> context.getString(R.string.iplist_interval_minutes, minutes)
    }

    private fun updatedAgo(context: Context, lastUpdate: Long): String {
        val age = System.currentTimeMillis() / 1000 - lastUpdate
        return when {
            age < 60 -> context.getString(R.string.iplist_updated_just_now)
            age < 3600 -> context.getString(R.string.iplist_updated_minutes_ago, (age / 60).toInt())
            age < 86400 -> context.getString(R.string.iplist_updated_hours_ago, (age / 3600).toInt())
            else -> context.getString(R.string.iplist_updated_days_ago, (age / 86400).toInt())
        }
    }

    fun dateTime(context: Context, epochSeconds: Long): String = DateUtils.formatDateTime(
        context, epochSeconds * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NUMERIC_DATE,
    )

    fun isHttpUrl(text: String): Boolean = text.startsWith("http://", true) || text.startsWith("https://", true)

    fun urlHost(text: String): String = try {
        Uri.parse(text).host.orEmpty()
    } catch (e: Exception) {
        ""
    }

    /** applyListTexts: the count, then whose result it is or where it comes from, how often and when it was updated. */
    fun subtitle(context: Context, list: IpList, ownerScanName: String?): String {
        val parts = arrayListOf(entries(context, list.entryCount))
        if (list.role == IpList.Role.ScanResult) {
            parts.add(
                if (ownerScanName.isNullOrEmpty()) context.getString(R.string.iplist_result_of_deleted_scan)
                else context.getString(R.string.iplist_result_of_scan, ownerScanName)
            )
        } else if (list.isRemote) {
            parts.add(context.getString(R.string.iplist_source_url_host, urlHost(list.source).ifEmpty { list.source }))
            if (list.autoUpdate) {
                parts.add(context.getString(R.string.iplist_auto_update_every, interval(context, maxOf(30, list.updateInterval))))
            }
            parts.add(if (list.lastUpdate > 0) updatedAgo(context, list.lastUpdate) else context.getString(R.string.iplist_never_updated))
        }
        return parts.joinToString(" · ")
    }

    fun error(context: Context, list: IpList): String =
        if (list.lastError.isEmpty()) "" else context.getString(R.string.iplist_last_update_failed, list.lastError)

    /** editIpListRejectedText: "3 unreadable (a, b, c)". */
    fun unreadable(context: Context, rejected: Int, samples: List<String>): String {
        var text = context.resources.getQuantityString(R.plurals.iplist_unreadable, rejected, number(rejected))
        if (samples.isNotEmpty()) text += " (" + samples.joinToString(", ") + ")"
        return text
    }

    /**
     * A one-line text prompt; [onDone] gets the trimmed text, or keeps the dialog open while [check] returns a
     * problem for it. [onText] follows the typed text, so a recreated screen can show the prompt again.
     */
    fun prompt(
        context: Context,
        title: CharSequence,
        label: CharSequence,
        text: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        placeholder: CharSequence? = null,
        suffix: CharSequence? = null,
        check: (String) -> String = { "" },
        onText: (String) -> Unit = {},
        onDone: (String) -> Unit,
    ): AlertDialog {
        val layout = TextInputLayout(context).apply {
            hint = label
            placeholderText = placeholder
            suffixText = suffix
        }
        val input = TextInputEditText(layout.context).apply {
            this.inputType = inputType
            isSingleLine = true
            setText(text)
            setSelection(text.length)
            doAfterTextChanged { onText(it?.toString().orEmpty()) }
        }
        layout.addView(input)
        val container = FrameLayout(context).apply {
            setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text?.toString()?.trim().orEmpty()
            val problem = check(value)
            if (problem.isNotEmpty()) {
                layout.error = problem
                return@setOnClickListener
            }
            dialog.dismiss()
            onDone(value)
        }
        input.requestFocus()
        return dialog
    }
}
