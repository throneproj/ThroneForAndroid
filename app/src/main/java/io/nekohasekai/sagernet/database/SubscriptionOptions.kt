package io.nekohasekai.sagernet.database

import android.os.Parcelable
import androidx.room.TypeConverter
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

/**
 * The per-group subscription options (`SubscriptionOptions`, Group.h:43-63) stored in `groups.sub_options_json`.
 * Empty strings inherit the global subscription settings; `removeUnavailable` and `sortByLatency` only take effect
 * while `urlTest` is on. The codec is Group.cpp:8-45: defaults are omitted, so all defaults are `{}`.
 */
@Parcelize
data class SubscriptionOptions(
    var userAgent: String = "",
    var sendHwid: SendHwid = SendHwid.KEEP_DEFAULT,
    var hwid: String = "",
    var hwidOs: String = "",
    var hwidOsVersion: String = "",
    var hwidModel: String = "",
    var keepWorking: Boolean = false,
    var removeDuplicates: Boolean = false,
    var removeInsecure: Boolean = false,
    var removeInvalid: Boolean = false,
    var urlTest: Boolean = false,
    var removeUnavailable: Boolean = false,
    var sortByLatency: Boolean = false,
    /** Android-only (#53): name filters applied on every update; desktop's codec drops these keys. */
    var nameInclude: String = "",
    var nameExclude: String = "",
    /** Android-only: the subscription info card above the profiles; stored only when off. */
    var showInfoCard: Boolean = true,
) : Parcelable {

    fun toJson(): JsonObject {
        val json = JsonObject()
        if (userAgent.isNotEmpty()) json["user_agent"] = userAgent
        if (sendHwid != SendHwid.KEEP_DEFAULT) json["send_hwid"] = sendHwid.value
        if (hwid.isNotEmpty()) json["hwid"] = hwid
        if (hwidOs.isNotEmpty()) json["hwid_os"] = hwidOs
        if (hwidOsVersion.isNotEmpty()) json["hwid_os_version"] = hwidOsVersion
        if (hwidModel.isNotEmpty()) json["hwid_model"] = hwidModel
        if (keepWorking) json["keep_working"] = true
        if (removeDuplicates) json["remove_duplicates"] = true
        if (removeInsecure) json["remove_insecure"] = true
        if (removeInvalid) json["remove_invalid"] = true
        if (urlTest) json["url_test"] = true
        if (removeUnavailable) json["remove_unavailable"] = true
        if (sortByLatency) json["sort_by_latency"] = true
        if (nameInclude.isNotEmpty()) json["name_include"] = nameInclude
        if (nameExclude.isNotEmpty()) json["name_exclude"] = nameExclude
        if (!showInfoCard) json["show_info_card"] = false
        return json
    }

    /** The column text: compact, keys sorted (QJsonDocument::toJson(Compact)). */
    fun toJsonString(): String = toJson().toCompact()

    companion object {

        @JvmStatic
        fun fromJson(json: JsonObject): SubscriptionOptions {
            val mode = json.int("send_hwid")
            return SubscriptionOptions(
                userAgent = json.string("user_agent"),
                sendHwid = if (mode == SendHwid.ON.value || mode == SendHwid.OFF.value) SendHwid.of(mode)
                else SendHwid.KEEP_DEFAULT,
                hwid = json.string("hwid"),
                hwidOs = json.string("hwid_os"),
                hwidOsVersion = json.string("hwid_os_version"),
                hwidModel = json.string("hwid_model"),
                keepWorking = json.bool("keep_working"),
                removeDuplicates = json.bool("remove_duplicates"),
                removeInsecure = json.bool("remove_insecure"),
                removeInvalid = json.bool("remove_invalid"),
                urlTest = json.bool("url_test"),
                removeUnavailable = json.bool("remove_unavailable"),
                sortByLatency = json.bool("sort_by_latency"),
                nameInclude = json.string("name_include"),
                nameExclude = json.string("name_exclude"),
                showInfoCard = !json.isBool("show_info_card") || json.bool("show_info_card"),
            )
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
