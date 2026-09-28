package io.nekohasekai.sagernet.bg

import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.config.ConfigGenerator
import java.io.File
import java.io.IOException

/**
 * The sing-box dashboard (SagerNet/sing-box-dashboard) that CI bundles into the assets (`sb-dashboard/`), unpacked into
 * the api service's dashboard dir once per install of the app, like the desktop's SeedDashboard
 * (mainwindow_system.cpp:438-455). The core serves a dir without its `.etag` file as user-provided and never downloads
 * over it (service/api/dashboard.go).
 */
object SingBoxDashboard {

    private const val ASSETS = "sb-dashboard"
    private const val INDEX = "index.html"

    private val coreDir: File get() = File(app.filesDir, CoreRuntime.WORKING_DIR)
    private val dir: File get() = File(coreDir, ConfigGenerator.DASHBOARD_PATH)

    /** The app install the unpacked copy comes from. */
    private val stamp: File get() = File(coreDir, "${ConfigGenerator.DASHBOARD_PATH}.stamp")

    /** Changes with every install of the app, a reinstall of the same version included; an update ends the process. */
    private val installVersion: String by lazy {
        val pm = app.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(app.packageName, 0)
        }
        info.lastUpdateTime.toString()
    }

    /**
     * Blocking, and never fails the start: a failure is only logged, and the dashboard screen then finds no page to
     * show.
     */
    fun install() {
        val temp = File(coreDir, "${ConfigGenerator.DASHBOARD_PATH}.unpack")
        try {
            val version = installVersion
            if (File(dir, INDEX).isFile && stamp.readTextOrNull() == version) return
            val files = assetFiles(ASSETS, "")
            if (INDEX !in files) {
                Logs.w("dashboard: this build bundles no sing-box dashboard")
                return
            }
            temp.deleteRecursively()
            for (path in files) {
                val target = File(temp, path)
                target.parentFile?.mkdirs()
                app.assets.open("$ASSETS/$path").use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            // Also replaces a copy the core downloaded itself, whose .etag would keep the core updating it.
            dir.deleteRecursively()
            if (!temp.renameTo(dir)) throw IOException("cannot rename ${temp.path} to ${dir.path}")
            stamp.writeText(version)
            Logs.i("dashboard: unpacked ${files.size} files")
        } catch (e: Exception) {
            Logs.w("dashboard: unpacking failed: ${e.readableMessage}")
            temp.deleteRecursively()
        }
    }

    /** The files below [assetDir], as paths relative to it: an entry without children is a file. */
    private fun assetFiles(assetDir: String, prefix: String): List<String> {
        val out = ArrayList<String>()
        for (name in app.assets.list(assetDir).orEmpty()) {
            val children = app.assets.list("$assetDir/$name").orEmpty()
            if (children.isEmpty()) out.add(prefix + name) else out.addAll(assetFiles("$assetDir/$name", "$prefix$name/"))
        }
        return out
    }

    private fun File.readTextOrNull(): String? = try {
        readText()
    } catch (e: IOException) {
        null
    }
}
