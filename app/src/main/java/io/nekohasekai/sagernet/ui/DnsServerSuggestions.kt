package io.nekohasekai.sagernet.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R

/**
 * The editable server combo boxes of DnsServerHistory::Fill: typed servers first, then the presets. Typing narrows the
 * list; the field's end icon opens all of it. The helper text is the desktop's tooltip on server lists.
 */
object DnsServerSuggestions {

    fun attach(layout: TextInputLayout, field: AutoCompleteTextView, suggestions: List<String>) {
        val adapter = SuggestionAdapter(field.context, suggestions)
        field.setAdapter(adapter)
        layout.helperText = field.context.getString(R.string.dns_server_list_hint)
        // The error icon would take the end icon's place.
        layout.setErrorIconDrawable(null)
        layout.setEndIconOnClickListener {
            if (field.isPopupShowing) {
                field.dismissDropDown()
                return@setEndIconOnClickListener
            }
            field.requestFocus()
            adapter.filter.filter(null) {
                if (field.isAttachedToWindow) field.showDropDown()
            }
        }
    }

    private class SuggestionAdapter(context: Context, private val all: List<String>) :
        ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, ArrayList(all)) {

        private val serverFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val typed = constraint?.toString()?.trim().orEmpty()
                // A picked server shows the whole list again rather than itself alone.
                val matches = if (typed.isEmpty() || typed in all) all else all.filter { it.contains(typed, true) }
                return FilterResults().apply {
                    values = matches
                    count = matches.size
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                setNotifyOnChange(false)
                clear()
                addAll(results.values as List<String>)
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = serverFilter
    }
}
