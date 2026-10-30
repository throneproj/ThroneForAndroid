package io.nekohasekai.sagernet.ui

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.utils.PackageCache

/**
 * "Add package name…" of the package pickers, for apps the list cannot show (hidden, not installed yet, restored from
 * a backup). Several names may be entered, separated by spaces, commas or new lines.
 */
object PackageNameInput {

    val PACKAGE_NAME = PackageCache.PACKAGE_NAME_REGEX

    fun isValid(name: String): Boolean = PackageCache.isValidPackageName(name)

    fun show(context: Context, onResult: (List<String>) -> Unit) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "com.example.app"
        }
        val container = FrameLayout(context).apply {
            setPadding(dp2px(24), dp2px(8), dp2px(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.picker_add_package_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val names = input.text.toString().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
                val invalid = names.filterNot { isValid(it) }
                if (invalid.isNotEmpty()) {
                    Toast.makeText(
                        context, context.getString(R.string.picker_invalid_package, invalid.joinToString(", ")),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                val valid = names.filter { isValid(it) }.distinct()
                if (valid.isNotEmpty()) onResult(valid)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
