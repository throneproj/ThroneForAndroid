package io.nekohasekai.sagernet.outbound.import

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonNull
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.vtr
import java.util.TreeMap

/**
 * One Clash `proxies:` entry with the field conversions of the desktop's clash::from_node / load_opt
 * (src/configs/sub/clash.cpp): a string field also accepts a number or a boolean, a bool field (MyBool) accepts only
 * true/false/1/0 (mihomo refuses yes/no/on/off), an int field (MyInt) a whole number in int range, as a number or as
 * decimal text. A present value of another shape keeps the default and, during an import, is reported. Nested
 * option blocks are exposed as [ClashProxy] views themselves so the per-type parsers read `ws-opts`, `smux`,
 * `reality-opts` ... the same way.
 */
class ClashProxy(@JvmField val node: JsonObject) {

    /** The `type` field lower-cased (the desktop matches it verbatim; the Android importer is case-insensitive). */
    val type: String get() = string("type").lowercase()

    fun has(key: String): Boolean = node.contains(key)

    /** Present and not null: an optional value (xhttpDownloadSettings) that otherwise takes the proxy's own. */
    fun present(key: String): Boolean = node.contains(key) && !node.isNull(key)

    /** load_opt<std::string>. */
    fun string(key: String): String = when (val value = node[key]) {
        is String -> value
        is Long -> value.toString()
        is Double -> formatDouble(value)
        is Boolean -> if (value) "true" else "false"
        else -> ""
    }

    /** load_opt<MyBool>. */
    fun bool(key: String): Boolean {
        val value = node[key]
        if (value == null || value === JsonNull) return false
        readBool(value)?.let { return it }
        if (ParseDiag.active()) ParseDiag.warn(key, vtr(R.string.validation_clash_expected_bool), nodeText(value))
        return false
    }

    /** load_opt<MyInt>. */
    fun int(key: String): Int {
        val value = node[key]
        if (value == null || value === JsonNull) return 0
        readInt(value)?.let { return it }
        if (ParseDiag.active()) ParseDiag.warn(key, vtr(R.string.validation_clash_expected_int), nodeText(value))
        return 0
    }

    /** A string or a sequence of strings (alpn, host-key, h2 host ...); scalars of other types are stringified. */
    fun strings(key: String): List<String> = when {
        node.isString(key) -> listOf(node.string(key))
        node.isArray(key) -> scalarStrings(node.array(key))
        else -> emptyList()
    }

    /**
     * WgReserved (clash.cpp) as the text Checks::WireguardReserved reads: a padded standard base64 string is mihomo's
     * []uint8 and a list of whole numbers the bytes themselves; any other value is kept as written. "" when absent.
     */
    fun reserved(key: String): String = when (val value = node[key]) {
        null, JsonNull -> ""
        is String -> {
            val bytes = if (value.toByteArray(Charsets.UTF_8).size % 4 == 0) Base64Strict.decode(value) else null
            bytes?.joinToString(",") { (it.toInt() and 0xFF).toString() } ?: value
        }
        is JsonArray -> {
            val bytes = value.map { readInt(it) }
            if (bytes.all { it != null }) bytes.joinToString(",") else value.joinToString(",") { nodeText(it) }
        }
        else -> nodeText(value)
    }

    /** A nested option block; an empty view when the key is absent or not a mapping. */
    fun obj(key: String): ClashProxy = ClashProxy(if (node.isObject(key)) node.obj(key) else JsonObject())

    /** std::map<std::string, std::string> (ws-opts headers), in the map's sorted key order. */
    fun stringMap(key: String): Map<String, String> {
        val out = TreeMap<String, String>()
        if (!node.isObject(key)) return out
        val obj = node.obj(key)
        for (k in obj.keys()) out[k] = ClashProxy(obj).string(k)
        return out
    }

    /** std::map<std::string, std::vector<std::string>> (http-opts headers). */
    fun stringListMap(key: String): Map<String, List<String>> {
        val out = TreeMap<String, List<String>>()
        if (!node.isObject(key)) return out
        val obj = node.obj(key)
        for (k in obj.keys()) out[k] = ClashProxy(obj).strings(k)
        return out
    }

    companion object {
        private fun scalarStrings(arr: JsonArray): List<String> = arr.mapNotNull {
            when (it) {
                is String -> it
                is Long -> it.toString()
                is Double -> formatDouble(it)
                is Boolean -> if (it) "true" else "false"
                else -> null
            }
        }

        /** QByteArray::number(v, 'g', 15): C's %g, which drops trailing zeros where Java's keeps them. */
        private fun formatDouble(v: Double): String = trimG(String.format(java.util.Locale.ROOT, "%.15g", v))

        private fun trimG(s: String): String {
            val e = s.indexOfFirst { it == 'e' || it == 'E' }
            val mantissa = if (e < 0) s else s.substring(0, e)
            val trimmed = if (mantissa.contains('.')) mantissa.trimEnd('0').trimEnd('.') else mantissa
            if (e < 0) return trimmed
            val exp = s.substring(e + 1).toInt()
            return trimmed + "e" + (if (exp < 0) "-" else "+") + Math.abs(exp).toString().padStart(2, '0')
        }

        /** clashReadInt (clash.cpp): a whole decimal number in int range, as a number or as trimmed text. */
        private fun readInt(value: Any): Int? = when (value) {
            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
            is String -> value.trim().let { if (Regex("[+-]?[0-9]+").matches(it)) it.toIntOrNull() else null }
            else -> null
        }

        /** clashReadBool (clash.cpp): only true/false/1/0. */
        private fun readBool(value: Any): Boolean? = when (value) {
            is Boolean -> value
            is Long -> when (value) {
                0L -> false
                1L -> true
                else -> null
            }

            is String -> when (value.trim().lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }

            else -> null
        }

        /** clashNodeText (clash.cpp): the value as shown next to a report. */
        private fun nodeText(value: Any): String = when (value) {
            is String -> value
            is Long -> value.toString()
            is Double -> formatDouble(value)
            is Boolean -> if (value) "true" else "false"
            is JsonArray -> "[…]"
            is JsonObject -> "{…}"
            else -> ""
        }
    }
}
