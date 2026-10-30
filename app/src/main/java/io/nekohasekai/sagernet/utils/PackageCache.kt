package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object PackageCache {

    /** UserHandle.PER_USER_RANGE (hidden API). */
    const val PER_USER_RANGE = 100000

    private const val RELOAD_DELAY_MS = 1000L

    val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isValidPackageName(name: String): Boolean = name == "android" || PACKAGE_NAME_REGEX.matches(name)

    /**
     * One complete listing. A reload builds the next one aside and publishes it whole, so readers (the core's
     * connection owner lookups on Go threads among them) never see a half-built map and never wait for a reload.
     */
    class Snapshot(
        internal val generation: Long,
        val installedPackages: Map<String, PackageInfo>,
        val installedApps: Map<String, ApplicationInfo>,
        val packageMap: Map<String, Int>,
        val uidMap: Map<Int, List<String>>,
    ) {
        private val labels = ConcurrentHashMap<String, String>()
        private val appIdMap by lazy { uidMap.mapKeys { it.key % PER_USER_RANGE } }

        /**
         * The packages of [uid]. The listing holds this user's apps only, so a uid of another user or profile (a dual
         * app, the work profile, the private space: userId * [PER_USER_RANGE] + appId) falls back to its app id.
         */
        fun packagesOf(uid: Int): List<String> = uidMap[uid] ?: appIdMap[uid % PER_USER_RANGE].orEmpty()

        fun loadLabel(packageName: String): String {
            labels[packageName]?.let { return it }
            val info = installedApps[packageName] ?: return packageName
            return info.loadLabel(app.packageManager).toString().also { labels[packageName] = it }
        }
    }

    @Volatile
    private var current: Snapshot? = null
    private val generations = AtomicLong()
    private val publishLock = Any()
    private val loaded = Mutex(true)
    private val registerd = AtomicBoolean(false)

    val installedPackages: Map<String, PackageInfo> get() = snapshot().installedPackages
    val installedApps: Map<String, ApplicationInfo> get() = snapshot().installedApps
    val packageMap: Map<String, Int> get() = snapshot().packageMap
    val uidMap: Map<Int, List<String>> get() = snapshot().uidMap

    /** The coalesced reload after package broadcasts; touched on the main thread only. */
    private var pendingReload: Job? = null

    // called from init (suspend)
    fun register() {
        if (registerd.getAndSet(true)) return
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // An update sends REMOVED (replacing) and then ADDED: the ADDED one is enough.
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                    intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                ) return
                // A burst (store updates) costs one reload, off the main thread, once it settles.
                pendingReload?.cancel()
                pendingReload = runOnIoDispatcher {
                    delay(RELOAD_DELAY_MS)
                    try {
                        reload()
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        })
        try {
            reload()
        } finally {
            loaded.unlock()
        }
    }

    /** Returns the published listing: this one, or a newer one that a later reload already put in place. */
    @SuppressLint("InlinedApi")
    fun reload(): Snapshot {
        val generation = generations.incrementAndGet()
        val rawPackageInfo = app.packageManager.getInstalledPackages(
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_PERMISSIONS
        )

        val installedPackages = rawPackageInfo.filter {
            when (it.packageName) {
                "android" -> true
                else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
            }
        }.associateBy { it.packageName }

        val installed = app.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val fresh = Snapshot(
            generation,
            installedPackages,
            installed.associateBy { it.packageName },
            installed.associate { it.packageName to it.uid },
            installed.groupBy({ it.uid }, { it.packageName }),
        )
        // Reloads run unserialized (after package broadcasts and for the app lists, both on IO), so one that queried
        // earlier must not replace a listing published by a later one.
        return synchronized(publishLock) {
            current?.takeIf { it.generation > generation } ?: fresh.also { current = it }
        }
    }

    /** The published listing, waiting for the first load when there is none yet. Take it once per lookup. */
    fun snapshot(): Snapshot = current ?: run {
        awaitLoadSync()
        // A failed first load must not hang or crash the core's owner lookups (Go callback threads).
        current ?: Snapshot(0, emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }

    operator fun get(uid: Int) = snapshot().uidMap[uid]
    operator fun get(packageName: String) = snapshot().packageMap[packageName]

    fun awaitLoadSync() {
        if (current != null) return
        register()
        runBlocking {
            loaded.withLock {
                // just await
            }
        }
    }

    fun loadLabel(packageName: String) = snapshot().loadLabel(packageName)

}