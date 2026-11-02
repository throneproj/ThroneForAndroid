package io.nekohasekai.sagernet.widget

import android.content.Context
import androidx.appcompat.widget.SearchView

/**
 * The profiles toolbar's search. Whenever the menu changes (selection mode, a ui mode switch) the toolbar sizes its
 * action items with an UNSPECIFIED measure; an expanded SearchView answers that with its unbounded max width, and the
 * toolbar then demotes the whole item group, search included, into a second overflow button. That pass gets the
 * collapsed width instead, so the items keep their places while the search is open.
 */
class ToolbarSearchView(context: Context) : SearchView(context) {

    private var collapsedWidth = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!isIconified && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(collapsedWidth, MeasureSpec.EXACTLY), heightMeasureSpec)
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (isIconified) collapsedWidth = measuredWidth
    }
}
