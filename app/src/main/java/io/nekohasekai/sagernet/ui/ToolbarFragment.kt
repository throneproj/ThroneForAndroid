package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.Theme

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    // 布局未包含 toolbar 或视图尚未创建时为 null，使用前需判空
    var toolbar: Toolbar? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        toolbar?.setNavigationIcon(R.drawable.ic_navigation_menu)
        // 纯白模式下工具栏为白底，菜单/标题/导航图标切换为深色保证可读
        if (Theme.isWhiteTheme()) {
            toolbar?.apply {
                setTitleTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                navigationIcon?.setTint(ContextCompat.getColor(requireContext(), R.color.black))
            }
        }
        toolbar?.setNavigationOnClickListener {
            (activity as? MainActivity)?.binding?.drawerLayout?.openDrawer(GravityCompat.START)
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
