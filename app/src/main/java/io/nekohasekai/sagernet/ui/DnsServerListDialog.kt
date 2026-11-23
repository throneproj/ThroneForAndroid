package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutDnsServerDialogBinding
import io.nekohasekai.sagernet.databinding.LayoutDnsServerListBinding
import io.nekohasekai.sagernet.databinding.LayoutDnsServerRowBinding
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.outbound.config.DnsServers

/**
 * DnsServerListEdit: one DNS server setting as reorderable rows, handed to [onAccept] as NormalizeDnsAddressList
 * stores it. The desktop edits the rows in place; here they live in a dialog, and a row or a custom address is typed
 * into a second one.
 */
class DnsServerListDialog(
    private val context: Context,
    private val title: CharSequence?,
    servers: String,
    presets: List<String>,
    history: List<String>,
    /** setPrimaryNote: the direct list's first server also resolves the proxy servers' own names. */
    private val primaryNote: Boolean,
    private val onAccept: (String) -> Unit,
) {

    private val addresses = DnsServers.splitDnsAddresses(servers).toMutableList()
    private val presetServers = DnsServers.splitDnsAddresses(presets.joinToString(","))
    private val recentServers = DnsServers.splitDnsAddresses(history.joinToString(","))
    private lateinit var binding: LayoutDnsServerListBinding
    private val rows = Rows()
    private val dragger = ItemTouchHelper(DragCallback())

    fun show() {
        val builder = MaterialAlertDialogBuilder(context)
        binding = LayoutDnsServerListBinding.inflate(LayoutInflater.from(builder.context))
        binding.servers.layoutManager = LinearLayoutManager(builder.context)
        binding.servers.adapter = rows
        dragger.attachToRecyclerView(binding.servers)
        binding.add.setOnClickListener { openAddMenu(it) }
        refresh()
        val dialog = builder
            .setTitle(title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Like the desktop form, a list that would be refused is not taken.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = DnsServers.normalizeDnsAddressList(addresses.joinToString(","))
            val problem = if (addresses.isEmpty()) {
                context.getString(R.string.dns_server_list_empty)
            } else {
                DnsServers.dnsAddressListProblem(value)
            }
            if (problem.isNotEmpty()) {
                binding.empty.isVisible = false
                binding.error.text = problem
                binding.error.isVisible = true
                return@setOnClickListener
            }
            dialog.dismiss()
            onAccept(value)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        rows.notifyDataSetChanged()
        binding.empty.isVisible = addresses.isEmpty()
        binding.raceHint.isVisible = addresses.size > 1
        binding.error.isVisible = false
    }

    /** fillAddMenu: the presets not listed yet, up to eight recent servers, then a custom address. */
    private fun openAddMenu(anchor: View) {
        val offered = presetServers.filterTo(ArrayList()) { it !in addresses }
        val firstRecent = offered.size
        for (address in recentServers) {
            if (offered.size - firstRecent == RECENT_LIMIT) break
            if (address in addresses || address in offered || DnsServers.dnsAddressListProblem(address).isNotEmpty()) continue
            offered.add(address)
        }
        val popup = PopupMenu(anchor.context, anchor)
        val menu = popup.menu
        for ((i, address) in offered.withIndex()) {
            if (i == firstRecent) menu.add(GROUP_RECENT, Menu.NONE, Menu.NONE, R.string.dns_server_recent).isEnabled = false
            menu.add(if (i < firstRecent) GROUP_PRESETS else GROUP_RECENT, i + 1, Menu.NONE, address)
        }
        menu.add(GROUP_CUSTOM, CUSTOM_ID, Menu.NONE, R.string.dns_server_custom)
        MenuCompat.setGroupDividerEnabled(menu, true)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == CUSTOM_ID) {
                promptAddress(null)
            } else {
                offered.getOrNull(item.itemId - 1)?.let { addServers(listOf(it)) }
            }
            true
        }
        popup.show()
    }

    private fun addServers(entered: List<String>) {
        val before = addresses.size
        for (address in entered) if (address !in addresses) addresses.add(address)
        if (addresses.size == before) return
        refresh()
        binding.servers.scrollToPosition(addresses.size - 1)
    }

    /** dnsListWithEdit: the row gives way to the servers typed in its place, less the ones listed already. */
    private fun replaceServer(row: Int, entered: List<String>) {
        if (row !in addresses.indices) return
        addresses.removeAt(row)
        var at = row
        for (address in entered) if (address !in addresses) addresses.add(at++, address)
        refresh()
    }

    /** openCustom ([row] null) or editServer; either may be given several servers at once. */
    private fun promptAddress(row: Int?) {
        val builder = MaterialAlertDialogBuilder(context)
        val prompt = LayoutDnsServerDialogBinding.inflate(LayoutInflater.from(builder.context))
        if (row != null) prompt.server.setText(addresses[row])
        val dialog = builder
            .setTitle(if (row == null) R.string.dns_server_custom else R.string.edit)
            .setView(prompt.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fun commit() {
            val text = prompt.server.text?.toString().orEmpty()
            val entered = DnsServers.splitDnsAddresses(text)
            // An empty custom address adds nothing; an emptied row is refused like any other bad address.
            if (row == null && entered.isEmpty()) {
                dialog.dismiss()
                return
            }
            val problem = DnsServers.dnsAddressListProblem(text)
            if (problem.isNotEmpty()) {
                prompt.serverLayout.error = problem
                return
            }
            dialog.dismiss()
            if (row == null) addServers(entered) else replaceServer(row, entered)
        }
        prompt.server.doAfterTextChanged { prompt.serverLayout.error = null }
        prompt.server.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            commit()
            true
        }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { commit() } }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        prompt.server.requestFocus()
        prompt.server.selectAll()
    }

    private inner class Rows : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount() = addresses.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(LayoutDnsServerRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(position)
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class RowHolder(val row: LayoutDnsServerRowBinding) : RecyclerView.ViewHolder(row.root) {
        private val addressColors: ColorStateList = row.address.textColors

        init {
            row.root.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { promptAddress(it) }
            }
            row.remove.setOnClickListener {
                val at = bindingAdapterPosition
                if (at == RecyclerView.NO_POSITION) return@setOnClickListener
                addresses.removeAt(at)
                refresh()
            }
            row.drag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && addresses.size > 1) dragger.startDrag(this)
                false
            }
        }

        fun bind(position: Int) {
            val address = addresses[position]
            val parsed = DnsServers.parseDnsAddress(address)
            row.address.text = address
            if (parsed.address == null) {
                row.address.setTextColor(context.getColorAttr(R.attr.colorError))
            } else {
                row.address.setTextColor(addressColors)
            }
            val note = when {
                parsed.address == null -> parsed.why
                primaryNote && position == 0 -> context.getString(R.string.dns_server_list_primary)
                else -> ""
            }
            row.note.text = note
            row.note.isVisible = note.isNotEmpty()
            row.drag.visibility = if (addresses.size > 1) View.VISIBLE else View.INVISIBLE
        }
    }

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
            if (addresses.size > 1) super.getDragDirs(recyclerView, viewHolder) else 0

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            addresses.add(to, addresses.removeAt(from))
            rows.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            // The first row's note follows the order.
            recyclerView.post { refresh() }
        }
    }

    private companion object {
        const val RECENT_LIMIT = 8
        const val GROUP_PRESETS = 1
        const val GROUP_RECENT = 2
        const val GROUP_CUSTOM = 3
        const val CUSTOM_ID = Int.MAX_VALUE
    }
}
