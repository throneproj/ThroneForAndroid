package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.applyTopInset
import io.nekohasekai.sagernet.ktx.openNavigation

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    // Null when the layout has no toolbar or the view is not created yet
    var toolbar: Toolbar? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<AppBarLayout>(R.id.appbar)?.applyTopInset()
        toolbar = view.findViewById(R.id.toolbar)
        toolbar?.setNavigationIcon(R.drawable.ic_navigation_menu)
        toolbar?.setNavigationContentDescription(R.string.navigation_open_drawer)
        // White theme: the toolbar is white, so the menu, title and navigation icon turn dark
        if (Theme.isWhiteTheme()) {
            toolbar?.apply {
                setTitleTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                navigationIcon?.setTint(ContextCompat.getColor(requireContext(), R.color.black))
            }
        }
        toolbar?.setNavigationOnClickListener {
            (activity as? MainActivity)?.binding?.drawerLayout?.openNavigation()
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
}
