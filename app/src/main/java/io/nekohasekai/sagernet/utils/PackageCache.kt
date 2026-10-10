package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object PackageCache {

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

    // called from init (suspend)
    fun register() {
        if (registerd.getAndSet(true)) return
        app.listenForPackageChanges(false) { reload() }
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
        // Reloads run unserialized (package broadcasts on the main thread, app lists on IO), so one that queried
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