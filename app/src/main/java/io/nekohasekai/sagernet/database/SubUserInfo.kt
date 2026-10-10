package io.nekohasekai.sagernet.database

import android.os.Parcelable
import androidx.room.TypeConverter
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import kotlinx.parcelize.Parcelize

/**
 * What a subscription's last fetch said about itself (`SubUserInfo`, Group.h:16-44): the Subscription-UserInfo quota
 * and expiry plus the provider's title, links, announcement and update interval, stored in `groups.sub_metadata_json`.
 * The codec is Group.cpp:10-42: nothing is written unless [valid], so the default is `{}`.
 */
@Parcelize
data class SubUserInfo(
    var valid: Boolean = false,
    var hasQuota: Boolean = false,
    var upload: Long = 0L,
    var download: Long = 0L,
    /** 0 with [hasQuota] = unlimited. */
    var total: Long = 0L,
    /** Epoch seconds, 0 = none. */
    var expire: Long = 0L,
    var title: String = "",
    var webUrl: String = "",
    var supportUrl: String = "",
    var announce: String = "",
    /** profile-update-interval in hours, 0 = none. */
    var serverInterval: Int = 0,
) : Parcelable {

    val used: Long get() = upload + download

    val remaining: Long get() = if (total > used) total - used else 0L

    val percentUsed: Double
        get() = if (total <= 0) 0.0 else (used.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0)

    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean = expire in 1 until nowSeconds

    fun toJson(): JsonObject {
        val json = JsonObject()
        if (!valid) return json
        json["valid"] = true
        json["has_quota"] = hasQuota
        if (upload > 0) json["upload"] = upload
        if (download > 0) json["download"] = download
        if (total > 0) json["total"] = total
        if (expire > 0) json["expire"] = expire
        if (title.isNotEmpty()) json["title"] = title
        if (webUrl.isNotEmpty()) json["web_url"] = webUrl
        if (supportUrl.isNotEmpty()) json["support_url"] = supportUrl
        if (announce.isNotEmpty()) json["announce"] = announce
        if (serverInterval > 0) json["server_interval"] = serverInterval
        return json
    }

    /** The column text: compact, keys sorted (QJsonDocument::toJson(Compact)). */
    fun toJsonString(): String = toJson().toCompact()

    companion object {

        private val USER_INFO = Regex("""\b(upload|download|total|expire)\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        fun fromJson(json: JsonObject): SubUserInfo {
            if (json.isEmpty()) return SubUserInfo()
            return SubUserInfo(
                valid = json.bool("valid"),
                hasQuota = json.bool("has_quota"),
                upload = json.variantLong("upload"),
                download = json.variantLong("download"),
                total = json.variantLong("total"),
                expire = json.variantLong("expire"),
                title = json.string("title"),
                webUrl = json.string("web_url"),
                supportUrl = json.string("support_url"),
                announce = json.string("announce"),
                serverInterval = json.int("server_interval"),
            )
        }

        /** A column that is not a JSON object reads as no info (GroupsRepo.cpp:233-236). */
        @JvmStatic
        fun parse(text: String?): SubUserInfo {
            if (text.isNullOrBlank()) return SubUserInfo()
            return JsonInput.parseObjectOrNull(text)?.let(::fromJson) ?: SubUserInfo()
        }

        /**
         * ParseSubUserInfo (Group.cpp:44-65): a Subscription-UserInfo value such as
         * `upload=1; download=2; total=3; expire=1700000000`. An expire in milliseconds is taken as seconds.
         */
        @JvmStatic
        fun parseHeader(info: String): SubUserInfo {
            val result = SubUserInfo()
            for (match in USER_INFO.findAll(info)) {
                val value = match.groupValues[2].toLongOrNull() ?: 0L
                when (match.groupValues[1].lowercase()) {
                    "upload" -> result.upload = value
                    "download" -> result.download = value
                    "total" -> {
                        result.total = value
                        result.hasQuota = true
                    }

                    else -> result.expire = if (value > 1_000_000_000_000L) value / 1000 else value
                }
                result.valid = true
            }
            return result
        }
    }

    class Converter {
        @TypeConverter
        fun toColumn(info: SubUserInfo): String = info.toJsonString()

        @TypeConverter
        fun fromColumn(text: String?): SubUserInfo = parse(text)
    }
}
