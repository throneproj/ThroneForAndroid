package io.nekohasekai.sagernet.database

import android.os.Parcelable
import androidx.room.TypeConverter
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import kotlinx.parcelize.Parcelize

/** Group.h:11-16, stored in `groups.test_sort_by`. */
enum class TestBy(val value: Int) {
    LATENCY(0), DL_SPEED(1), UL_SPEED(2), IP_OUT(3);

    companion object {
        @JvmStatic
        fun of(value: Int): TestBy = entries.firstOrNull { it.value == value } ?: LATENCY
    }
}

/** Group.h:18-23, stored in `groups.test_items_to_show`. */
enum class TestShowItems(val value: Int) {
    ALL(0), NONE(1), IP_ONLY(2), SPEED_ONLY(3);

    val showSpeed: Boolean get() = this == ALL || this == SPEED_ONLY
    val showIp: Boolean get() = this == ALL || this == IP_ONLY

    companion object {
        @JvmStatic
        fun of(value: Int): TestShowItems = entries.firstOrNull { it.value == value } ?: ALL
    }
}

/** Group.h:25-29, stored in `groups.traffic_sort_by`. */
enum class TrafficBy(val value: Int) {
    TOTAL(0), DL(1), UL(2);

    companion object {
        @JvmStatic
        fun of(value: Int): TrafficBy = entries.firstOrNull { it.value == value } ?: TOTAL
    }
}

/** Group.h:31-34, stored in `groups.type_sort_by`. */
enum class TypeBy(val value: Int) {
    BY_TYPE(0), BY_SECURITY(1);

    companion object {
        @JvmStatic
        fun of(value: Int): TypeBy = entries.firstOrNull { it.value == value } ?: BY_TYPE
    }
}

/** Group.h:36-41; the value doubles as the index of the "Send HWID" menu. */
enum class SendHwid(val value: Int) {
    KEEP_DEFAULT(0), ON(1), OFF(2);

    companion object {
        @JvmStatic
        fun of(value: Int): SendHwid = entries.firstOrNull { it.value == value } ?: KEEP_DEFAULT
    }
}

/** Group.h subTlsVersion (sub_tls_version, sub_options `tls_version`); persisted as ints, append only. */
enum class SubTlsVersion(val value: Int) {
    AUTO(0), TLS12(1), TLS13(2);

    companion object {
        @JvmStatic
        fun of(value: Int): SubTlsVersion? = entries.firstOrNull { it.value == value }
    }
}

/** Group.h subHttpVersion (sub_http_version, sub_options `http_version`); persisted as ints, append only. */
enum class SubHttpVersion(val value: Int) {
    AUTO(0), HTTP11(1);

    companion object {
        @JvmStatic
        fun of(value: Int): SubHttpVersion? = entries.firstOrNull { it.value == value }
    }
}

/**
 * The per-group subscription options (`SubscriptionOptions`, Group.h) stored in `groups.sub_options_json`.
 * Empty strings and null values inherit the global subscription settings; `removeUnavailable` and `sortByLatency`
 * only take effect while `urlTest` is on. The codec is Group.cpp ToJson/FromJson: defaults are omitted, so all
 * defaults are `{}`.
 */
@Parcelize
data class SubscriptionOptions(
    var userAgent: String = "",
    var tlsVersion: SubTlsVersion? = null,
    var httpVersion: SubHttpVersion? = null,
    var sendHwid: SendHwid = SendHwid.KEEP_DEFAULT,
    var hwid: String = "",
    var hwidOs: String = "",
    var hwidOsVersion: String = "",
    var hwidModel: String = "",
    /** Minutes, at least 30; 0 follows sub_auto_update. */
    var updateInterval: Int = 0,
    /** Null follows sub_respect_server_interval. */
    var respectServerInterval: Boolean? = null,
    var keepWorking: Boolean = false,
    var removeDuplicates: Boolean = false,
    var removeInsecure: Boolean = false,
    var removeInvalid: Boolean = false,
    var urlTest: Boolean = false,
    var removeUnavailable: Boolean = false,
    var sortByLatency: Boolean = false,
    /** Case-insensitive regexes on the profile name, applied on every update; the exclusion wins. */
    var nameFilter: String = "",
    var nameExclude: String = "",
    /** SubscriptionNameFilter.PROTOCOLS ids whose profiles every update drops; unknown ids are kept as they are. */
    var typeExclude: List<String> = emptyList(),
    /** The desktop's IP-test country filter ("HK,JP" keeps, "!RU" drops): carried, not applied on Android. */
    var countryFilter: String = "",
    /** Android-only: the subscription info card above the profiles; stored only when off. */
    var showInfoCard: Boolean = true,
) : Parcelable {

    fun toJson(): JsonObject {
        val json = JsonObject()
        if (userAgent.isNotEmpty()) json["user_agent"] = userAgent
        tlsVersion?.let { json["tls_version"] = it.value }
        httpVersion?.let { json["http_version"] = it.value }
        if (sendHwid != SendHwid.KEEP_DEFAULT) json["send_hwid"] = sendHwid.value
        if (hwid.isNotEmpty()) json["hwid"] = hwid
        if (hwidOs.isNotEmpty()) json["hwid_os"] = hwidOs
        if (hwidOsVersion.isNotEmpty()) json["hwid_os_version"] = hwidOsVersion
        if (hwidModel.isNotEmpty()) json["hwid_model"] = hwidModel
        if (updateInterval > 0) json["update_interval"] = updateInterval
        respectServerInterval?.let { json["respect_server_interval"] = it }
        if (keepWorking) json["keep_working"] = true
        if (removeDuplicates) json["remove_duplicates"] = true
        if (removeInsecure) json["remove_insecure"] = true
        if (removeInvalid) json["remove_invalid"] = true
        if (urlTest) json["url_test"] = true
        if (removeUnavailable) json["remove_unavailable"] = true
        if (sortByLatency) json["sort_by_latency"] = true
        if (nameFilter.isNotEmpty()) json["name_filter"] = nameFilter
        if (nameExclude.isNotEmpty()) json["name_exclude"] = nameExclude
        if (typeExclude.isNotEmpty()) json["type_exclude"] = JsonArray().also { a -> typeExclude.forEach { a.add(it) } }
        if (countryFilter.isNotEmpty()) json["country_filter"] = countryFilter
        if (!showInfoCard) json["show_info_card"] = false
        return json
    }

    /** SubscriptionOptions::SameFilters: whether an update would keep the same profiles. */
    fun sameFilters(other: SubscriptionOptions): Boolean =
        nameFilter == other.nameFilter && nameExclude == other.nameExclude &&
            typeExclude.toSet() == other.typeExclude.toSet() && countryFilter == other.countryFilter

    /** The column text: compact, keys sorted (QJsonDocument::toJson(Compact)). */
    fun toJsonString(): String = toJson().toCompact()

    companion object {

        @JvmStatic
        fun fromJson(json: JsonObject): SubscriptionOptions {
            val mode = json.int("send_hwid")
            return SubscriptionOptions(
                userAgent = json.string("user_agent"),
                tlsVersion = json.intOrNull("tls_version")?.let(SubTlsVersion::of),
                httpVersion = json.intOrNull("http_version")?.let(SubHttpVersion::of),
                sendHwid = if (mode == SendHwid.ON.value || mode == SendHwid.OFF.value) SendHwid.of(mode)
                else SendHwid.KEEP_DEFAULT,
                hwid = json.string("hwid"),
                hwidOs = json.string("hwid_os"),
                hwidOsVersion = json.string("hwid_os_version"),
                hwidModel = json.string("hwid_model"),
                updateInterval = maxOf(json.int("update_interval"), 0),
                respectServerInterval = if (json.isBool("respect_server_interval")) json.bool("respect_server_interval") else null,
                keepWorking = json.bool("keep_working"),
                removeDuplicates = json.bool("remove_duplicates"),
                removeInsecure = json.bool("remove_insecure"),
                removeInvalid = json.bool("remove_invalid"),
                urlTest = json.bool("url_test"),
                removeUnavailable = json.bool("remove_unavailable"),
                sortByLatency = json.bool("sort_by_latency"),
                // name_include: what Android wrote before the desktop named the key.
                nameFilter = if (json.contains("name_filter")) json.string("name_filter") else json.string("name_include"),
                nameExclude = json.string("name_exclude"),
                typeExclude = typeIds(json.array("type_exclude").strings()),
                countryFilter = json.string("country_filter"),
                showInfoCard = !json.isBool("show_info_card") || json.bool("show_info_card"),
            )
        }

        /** Trimmed, non-empty, first occurrence kept (Group.cpp FromJson). */
        @JvmStatic
        fun typeIds(ids: Iterable<String>): List<String> =
            ids.mapNotNullTo(LinkedHashSet()) { id -> id.trim().takeIf { it.isNotEmpty() } }.toList()

        /** QJsonValue::toInt(-1): null unless a whole number that fits an int. */
        private fun JsonObject.intOrNull(key: String): Int? = when (val v = this[key]) {
            is Long -> if (v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) v.toInt() else null
            is Double -> if (v == Math.rint(v) && v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) v.toInt() else null
            else -> null
        }

        /** A column that is not a JSON object reads as all defaults (GroupsRepo.cpp:212-215). */
        @JvmStatic
        fun parse(text: String?): SubscriptionOptions {
            if (text.isNullOrBlank()) return SubscriptionOptions()
            return JsonInput.parseObjectOrNull(text)?.let(::fromJson) ?: SubscriptionOptions()
        }
    }

    class Converter {
        @TypeConverter
        fun toColumn(options: SubscriptionOptions): String = options.toJsonString()

        @TypeConverter
        fun fromColumn(text: String?): SubscriptionOptions = parse(text)
    }
}
