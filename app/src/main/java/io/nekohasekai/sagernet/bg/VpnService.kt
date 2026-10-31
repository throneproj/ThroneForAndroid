package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.PlatformNotifications
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.RoutePrefix
import io.throneproj.mobile.RoutePrefixIterator
import io.throneproj.mobile.StringIterator
import io.throneproj.mobile.TunOptions
import java.net.InetAddress
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(),
    BaseService.Interface {

    companion object {

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"

    }

    var conn: ParcelFileDescriptor? = null

    private var metered = false

    override var upstreamInterfaceName: String? = null

    override suspend fun startProcesses() {
        DataStore.vpnService = this
        super.startProcesses() // launch proxy instance
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override suspend fun killProcesses(): Throwable? {
        val currentConnection = conn
        var cleanupError: Throwable? = null
        Logs.i(
            "VpnLifecycleTrace stage=tun-close begin " +
                "hasConnection=${currentConnection != null}"
        )
        try {
            currentConnection?.close()
            Logs.i("VpnLifecycleTrace stage=tun-close success")
        } catch (error: Throwable) {
            Logs.w(
                "VpnLifecycleTrace stage=tun-close failed " +
                    "type=${error.javaClass.name} message=${error.message}"
            )
            cleanupError = error
        } finally {
            conn = null
        }
        super.killProcesses()?.let { error ->
            if (cleanupError == null) {
                cleanupError = error
            } else if (cleanupError !== error) {
                cleanupError?.addSuppressed(error)
            }
        }
        Logs.i(
            "VpnLifecycleTrace stage=kill done hasCleanupError=${cleanupError != null}"
        )
        return cleanupError
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"
    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-vpn")

    private fun isAlwaysOnVpn(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching { isAlwaysOn }.getOrDefault(false)

    override fun onNoProfile() {
        if (isAlwaysOnVpn()) PlatformNotifications.alwaysOnNoProfile(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The system starts an always-on VPN whatever the service mode says; the rest of the app follows the mode.
        if (DataStore.serviceMode != Key.MODE_VPN && isAlwaysOnVpn()) DataStore.serviceMode = Key.MODE_VPN
        if (DataStore.serviceMode == Key.MODE_VPN) {
            if (prepare(this) != null) {
                startActivity(
                    Intent(
                        this, VpnRequestActivity::class.java
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    inner class NullConnectionException : NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    // The generated tun inbound is the only source of truth for the interface layout.
    fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing VPN permission")
        val builder = Builder()
            .setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(options.getMTU())

        options.getInet4Address().forEach { builder.addAddress(it.address(), it.prefix()) }
        options.getInet6Address().forEach { builder.addAddress(it.address(), it.prefix()) }

        if (options.getAutoRoute()) {
            if (options.getDNSMode().value != Mobile.DNSModeDisabled) {
                options.getDNSServerAddress().toList().forEach { builder.addDnsServer(it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // A family gets its default route only when the tun has an address of it: without any address or
                // route of a family (and no allowFamily call) Android blocks that family, so IPv6 fails fast
                // instead of vanishing into a tun that has no IPv6 address (the core does the same below API 33).
                val inet4RouteAddress = options.getInet4RouteAddress()
                if (inet4RouteAddress.hasNext()) {
                    inet4RouteAddress.forEach { builder.addRoute(it.address(), it.prefix()) }
                } else if (options.getInet4Address().hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }
                val inet6RouteAddress = options.getInet6RouteAddress()
                if (inet6RouteAddress.hasNext()) {
                    inet6RouteAddress.forEach { builder.addRoute(it.address(), it.prefix()) }
                } else if (options.getInet6Address().hasNext()) {
                    builder.addRoute("::", 0)
                }
                // Builder.check rejects a loopback prefix ("Bad address"), and loopback never enters the tun anyway.
                options.getInet4RouteExcludeAddress().forEach {
                    val address = InetAddress.getByName(it.address())
                    if (!address.isLoopbackAddress) builder.excludeRoute(IpPrefix(address, it.prefix()))
                }
                options.getInet6RouteExcludeAddress().forEach {
                    val address = InetAddress.getByName(it.address())
                    if (!address.isLoopbackAddress) builder.excludeRoute(IpPrefix(address, it.prefix()))
                }
            } else {
                // Builder.excludeRoute only exists from API 33; below that the core pre-splits
                // "everything minus the excludes" into plain ranges that replace the default routes.
                options.getInet4RouteRange().forEach { builder.addRoute(it.address(), it.prefix()) }
                options.getInet6RouteRange().forEach { builder.addRoute(it.address(), it.prefix()) }
            }
        }

        val includePackage = options.getIncludePackage()
        if (includePackage.hasNext()) {
            val added = includePackage.toList().count {
                try {
                    builder.addAllowedApplication(it)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
            // None of the chosen apps is installed: an empty allow list would capture every app instead of none.
            if (added == 0) builder.addAllowedApplication(packageName)
        }
        val excludePackage = options.getExcludePackage()
        if (excludePackage.hasNext()) {
            excludePackage.toList().forEach {
                try {
                    builder.addDisallowedApplication(it)
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && options.isHTTPProxyEnabled()) {
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    options.getHTTPProxyServer(),
                    options.getHTTPProxyServerPort(),
                    options.getHTTPProxyBypassDomain().toList().filter { it.isNotBlank() },
                )
            )
        }

        updateUnderlyingNetwork(builder)
        metered = DataStore.meteredNetwork
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)
        val connection = builder.establish() ?: throw NullConnectionException()
        conn = connection
        return connection.fd
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        val networks = SagerNet.underlyingNetwork?.let { arrayOf(it) }
        if (builder != null) {
            if (networks != null) builder.setUnderlyingNetworks(networks)
        } else {
            // null once the network is lost: the VPN follows the system default instead of declaring a gone network.
            setUnderlyingNetworks(networks)
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        DataStore.vpnService = null
        super.onDestroy()
        data.binder.close()
    }
}

private inline fun RoutePrefixIterator.forEach(block: (RoutePrefix) -> Unit) {
    while (hasNext()) block(next())
}

private fun StringIterator.toList(): List<String> {
    val values = ArrayList<String>()
    while (hasNext()) values.add(next())
    return values
}
