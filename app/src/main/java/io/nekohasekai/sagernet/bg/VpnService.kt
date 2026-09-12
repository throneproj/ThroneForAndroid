package io.nekohasekai.sagernet.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.Subnet
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

    override var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:vpn")
            .apply { acquire() }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    fun startVpn(tunOptionsJson: String, tunPlatformOptionsJson: String): Int {
//        Logs.d(tunOptionsJson)
//        Logs.d(tunPlatformOptionsJson)
//        val tunOptions = JSONObject(tunOptionsJson)

        // address & route & MTU ...... use NB4A GUI config
        val builder = Builder().setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(DataStore.mtu)
        // address
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        // 即使禁用 IPv6 也常驻 v6 虚拟地址：让系统完整接管 IPv6 流量进入 tun，
        // 避免未添加 v6 配置时流量经物理网卡旁路泄露
        builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
        builder.addDnsServer(PRIVATE_VLAN4_ROUTER)

        // route
        if (DataStore.bypassLan) {
            resources.getStringArray(R.array.bypass_private_route).forEach {
                val subnet = Subnet.fromString(it)!!
                builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
            }
            builder.addRoute(PRIVATE_VLAN4_ROUTER, 32)
            builder.addRoute(FAKEDNS_VLAN4_CLIENT, 15)
            // https://issuetracker.google.com/issues/149636790
            // 禁用 IPv6 时同样常驻 v6 路由（公网段 + ULA 段），由内核策略拒绝 v6 流量
            builder.addRoute("2000::", 3)
            builder.addRoute("fc00::", 7)
        } else {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            builder.addRoute("fc00::", 7)
        }

        updateUnderlyingNetwork(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)

        // app route
        val packageName = packageName
        val proxyApps = DataStore.proxyApps
        var bypass = DataStore.bypass
        val workaroundSYSTEM = false /* DataStore.tunImplementation == TunImplementation.SYSTEM */
        val needBypassRootUid = workaroundSYSTEM || data.proxy!!.config.trafficMap.values.any {
            it[0].hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP
        }

        if (proxyApps || needBypassRootUid) {
            val individual = mutableSetOf<String>()
            val allApps by lazy {
                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter {
                    when (it.packageName) {
                        packageName -> false
                        "android" -> true
                        else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                    }
                }.map {
                    it.packageName
                }
            }
            if (proxyApps) {
                individual.addAll(DataStore.individual.split('\n').filter { it.isNotBlank() })
                if (bypass && needBypassRootUid) {
                    val individualNew = allApps.toMutableList()
                    individualNew.removeAll(individual)
                    individual.clear()
                    individual.addAll(individualNew)
                    bypass = false
                }
            } else {
                individual.addAll(allApps)
                bypass = false
            }

            val added = mutableListOf<String>()

            individual.apply {
                // Allow Matsuri itself using VPN.
                remove(packageName)
                if (!bypass) add(packageName)
            }.forEach {
                try {
                    if (bypass) {
                        builder.addDisallowedApplication(it)
                    } else {
                        builder.addAllowedApplication(it)
                    }
                    added.add(it)
                } catch (ex: PackageManager.NameNotFoundException) {
                    Logs.w(ex)
                }
            }

            if (bypass) {
                Logs.d("Add bypass: ${added.joinToString(", ")}")
            } else {
                Logs.d("Add allow: ${added.joinToString(", ")}")
            }
        }

        // 混合入站存在时始终向系统追加 HTTP 代理（Android 10+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !DataStore.mixedInboundDisabled) {
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    LOCALHOST,
                    DataStore.mixedPort,
                    DataStore.httpProxyBypass.lines().mapNotNull { line ->
                        line.trim().takeIf { it.isNotBlank() && !it.startsWith("#") }
                    },
                )
            )
        }

        metered = DataStore.meteredNetwork
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)
        conn = builder.establish() ?: throw NullConnectionException()

        return conn!!.fd
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            SagerNet.underlyingNetwork?.let {
                builder?.setUnderlyingNetworks(arrayOf(SagerNet.underlyingNetwork))
                    ?: setUnderlyingNetworks(arrayOf(SagerNet.underlyingNetwork))
            }
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        DataStore.vpnService = null
        super.onDestroy()
        data.binder.close()
    }
}
