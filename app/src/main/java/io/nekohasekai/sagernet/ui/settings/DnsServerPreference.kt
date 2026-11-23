package io.nekohasekai.sagernet.ui.settings

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.EditTextPreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.DnsServerListDialog

/**
 * A DNS server setting edited as a list (DnsServerListEdit) and stored as its comma list; the change listeners see
 * the list before it is stored, as they would a typed text.
 */
class DnsServerPreference
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context, R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    ),
    defStyleRes: Int = 0
) : EditTextPreference(context, attrs, defStyleAttr, defStyleRes) {

    var presets: List<String> = emptyList()

    /** The servers saved before, read each time the editor opens. */
    var recent: () -> List<String> = { emptyList() }

    var primaryNote = false

    override fun onClick() {
        DnsServerListDialog(context, title, text.orEmpty(), presets, recent(), primaryNote) { value ->
            if (callChangeListener(value)) text = value
        }.show()
    }
}
