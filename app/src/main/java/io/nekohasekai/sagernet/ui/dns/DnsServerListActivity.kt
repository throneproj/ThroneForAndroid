package io.nekohasekai.sagernet.ui.dns

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DnsServerEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsServerListActivity : ThemedActivity(R.layout.layout_app_list) {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DnsServerAdapter
    private lateinit var toolbar: Toolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setTitle(R.string.dns_servers_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        recyclerView = findViewById(R.id.list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = DnsServerAdapter(emptyList()) { server ->
            startActivity(Intent(this, DnsServerEditActivity::class.java).putExtra("server_id", server.id))
        }
        supportFragmentManager.setFragmentResultListener("refresh", this) { _, _ -> loadServers() }
        loadServers()
    }

    private fun loadServers() {
        lifecycleScope.launch {
            val servers = withContext(Dispatchers.IO) { SagerDatabase.instance.dnsServerDao().list() }
            adapter = DnsServerAdapter(servers) { server ->
                startActivity(Intent(this@DnsServerListActivity, DnsServerEditActivity::class.java).putExtra("server_id", server.id))
            }
            recyclerView.adapter = adapter
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }
}

class DnsServerAdapter(
    private val items: List<DnsServerEntity>,
    private val onItemClick: (DnsServerEntity) -> Unit,
) : RecyclerView.Adapter<DnsServerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tagView: TextView = view.findViewById(R.id.title)
        val addressView: TextView = view.findViewById(R.id.desc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.layout_apps_item, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tagView.text = item.tag
        holder.addressView.text = "${item.address} (${item.type})"
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size
}
