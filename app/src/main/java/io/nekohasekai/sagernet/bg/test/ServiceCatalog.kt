package io.nekohasekai.sagernet.bg.test

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import java.io.File
import java.io.IOException

/**
 * The categories and checks of a ParseServiceChecks description ({version, revision, categories, checks}), both in
 * definition order (ServiceChecks::Definitions). :bg keeps the description of the copy it last picked in [FILE], so
 * the main process, which has no core, can list the services.
 */
class ServiceCatalog(val categories: List<Category>, val checks: List<Check>) {

    class Category(val id: String, val name: String, val icon: String)

    class Check(val id: String, val name: String, val category: String)

    /** ServiceChecks::Enabled: the check ids in definition order, minus [disabled]. */
    fun enabled(disabled: Collection<String>): List<String> = checks.map { it.id }.filter { it !in disabled }

    companion object {
        private const val FILE = "service_checks.list.json"

        val EMPTY = ServiceCatalog(emptyList(), emptyList())

        fun parse(description: JsonObject) = ServiceCatalog(
            description.array("categories").mapNotNull { value ->
                (value as? JsonObject)?.let { Category(it.string("id"), it.string("name"), it.string("icon")) }
            },
            description.array("checks").mapNotNull { value ->
                (value as? JsonObject)?.let { Check(it.string("id"), it.string("name"), it.string("category")) }
            },
        )

        private val file: File get() = File(app.noBackupFilesDir, FILE)

        /** The description the last service check in :bg ran with; null before the first one. */
        fun saved(): ServiceCatalog? {
            val text = try {
                file.takeIf { it.isFile }?.readText() ?: return null
            } catch (e: IOException) {
                Logs.w(e)
                return null
            }
            return JsonInput.parseObjectOrNull(text)?.let(::parse)?.takeIf { it.checks.isNotEmpty() }
        }

        /** :bg: keeps the description [text] for [saved], replacing the file only when it changed. */
        internal fun save(text: String) {
            try {
                val target = file
                if (target.isFile && target.readText() == text) return
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    Logs.w("Cannot write ${target.name}")
                }
            } catch (e: IOException) {
                Logs.w(e)
            }
        }
    }
}
