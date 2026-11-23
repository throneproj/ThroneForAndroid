package io.nekohasekai.sagernet.group

import android.util.AtomicFile
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import java.io.File
import java.io.FileNotFoundException

/**
 * GroupsRepo::CountryVerdicts: exit countries from earlier IP tests, keyed by profile content key, per group. The
 * country filter's memory of profiles it already removed, so a refresh does not import and test them again. A file
 * per group instead of a table: losing it only means testing again.
 */
internal object SubscriptionCountryVerdicts {

    private const val DIR = "sub_country_verdicts"

    private fun file(gid: Long) = AtomicFile(File(File(app.noBackupFilesDir, DIR), "$gid.json"))

    fun load(gid: Long): HashMap<String, String> {
        val verdicts = HashMap<String, String>()
        val json = try {
            JsonInput.parseObjectOrNull(String(file(gid).readFully(), Charsets.UTF_8))
        } catch (_: FileNotFoundException) {
            null
        } catch (e: Exception) {
            Logs.w(e)
            null
        } ?: return verdicts
        for ((key, _) in json) {
            val country = json.string(key)
            if (key.isNotEmpty() && country.isNotEmpty()) verdicts[key] = country
        }
        return verdicts
    }

    fun save(gid: Long, verdicts: Map<String, String>) {
        val file = file(gid)
        if (verdicts.isEmpty()) {
            file.delete()
            return
        }
        val json = JsonObject()
        for ((key, country) in verdicts) json[key] = country
        file.baseFile.parentFile?.mkdirs()
        val stream = try {
            file.startWrite()
        } catch (e: Exception) {
            Logs.w(e)
            return
        }
        try {
            stream.write(json.toCompact().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            Logs.w(e)
        }
    }

    /** The group was deleted. */
    fun clear(gid: Long) = file(gid).delete()
}
