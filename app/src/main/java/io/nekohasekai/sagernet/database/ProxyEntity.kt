package io.nekohasekai.sagernet.database

import android.os.Parcelable
import androidx.annotation.ColorInt
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import io.nekohasekai.sagernet.outbound.InvalidOutbound
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.types.Chain
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * One stored profile: the desktop's `profiles` row (ProfilesRepo.cpp:17-43) — [type] and [outboundJson] (the
 * compact key-sorted ExportToJson that is the data contract), [name] (a copy of the outbound's name), the test
 * results and the traffic — plus the Android-only [userOrder] (the dense 0..n-1 position in the group, the desktop's
 * `profiles_json` index) and [testError]. The parsed [outbound] is derived from the two contract columns and cached;
 * [putOutbound] is the way to change them.
 */
@Entity(
    tableName = ProxyEntity.TABLE,
    foreignKeys = [
        ForeignKey(
            entity = ProxyGroup::class,
            parentColumns = ["id"],
            childColumns = ["gid"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    // list order: a group's members are read in (user_order, id) order without a sort
    indices = [Index("gid", "user_order", "id")],
)
@Parcelize
data class ProxyEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    @ColumnInfo(name = "type") var type: String = "",
    @ColumnInfo(name = "name") var name: String? = null,
    @ColumnInfo(name = "gid", defaultValue = "0") var groupId: Long = 0L,
    @ColumnInfo(name = "user_order", defaultValue = "0") var userOrder: Long = 0L,
    /** 0 untested, > 0 ms, -1 failed, [LATENCY_CONNECT_ONLY]; write it through [recordLatency]. */
    @ColumnInfo(name = "latency", defaultValue = "0") var latency: Int = 0,
    /** Epoch seconds of the latency measurement, 0 = never. */
    @ColumnInfo(name = "latency_at", defaultValue = "0") var latencyAt: Long = 0L,
    /** The core's rate strings ("12.34Mbps"), "N/A" after a failed speed test. */
    @ColumnInfo(name = "dl_speed") var dlSpeed: String? = null,
    @ColumnInfo(name = "ul_speed") var ulSpeed: String? = null,
    /** ISO country code of the egress. */
    @ColumnInfo(name = "test_country") var testCountry: String? = null,
    @ColumnInfo(name = "ip_out") var ipOut: String? = null,
    @ColumnInfo(name = "outbound_json") var outboundJson: String = "",
    /** Downloaded bytes (`traffic_dl`). */
    @ColumnInfo(name = "traffic_dl", defaultValue = "0") var rx: Long = 0L,
    /** Uploaded bytes (`traffic_up`). */
    @ColumnInfo(name = "traffic_up", defaultValue = "0") var tx: Long = 0L,
    /** The last test's error text; Android-only, never exported. */
    @ColumnInfo(name = "test_error") var testError: String? = null,
) : Parcelable {

    @IgnoredOnParcel
    @Ignore
    private var cachedOutbound: Outbound? = null

    @IgnoredOnParcel
    @Ignore
    private var cachedType: String? = null

    @IgnoredOnParcel
    @Ignore
    private var cachedJson: String? = null

    /** The parsed profile: an [InvalidOutbound] when the type is unknown or [outboundJson] is not a JSON object. */
    @get:Ignore
    val outbound: Outbound
        get() = cachedOrNull() ?: parseOutbound(type, outboundJson).also {
            cachedOutbound = it
            cachedType = type
            cachedJson = outboundJson
        }

    // String.equals checks identity first, so the usual unchanged case costs no comparison of the JSON.
    private fun cachedOrNull(): Outbound? = cachedOutbound?.takeIf { cachedType == type && cachedJson == outboundJson }

    /** Stores [o] as the profile's data (type + compact ExportToJson + name) and keeps it as the parsed instance. */
    fun putOutbound(o: Outbound): ProxyEntity {
        type = o.type
        name = o.name
        outboundJson = o.exportToJson().toCompact()
        cachedOutbound = o
        cachedType = type
        cachedJson = outboundJson
        return this
    }

    /**
     * The lowercase display name, type and address on separate lines, what the profile search matches. An outbound
     * that is not parsed yet is parsed for this without being kept.
     */
    fun searchText(): String {
        val o = cachedOrNull() ?: parseOutbound(type, outboundJson)
        val displayType = o.displayType().ifEmpty { type }
        return (o.displayName().ifEmpty { displayType } + "\n" + displayType + "\n" + o.displayAddress()).lowercase()
    }

    fun displayName(): String = outbound.displayName().ifEmpty { displayType() }

    fun displayAddress(): String = outbound.displayAddress()

    fun displayType(): String = outbound.displayType().ifEmpty { type }

    /** The share link of the profile, "" for the types that have none. */
    fun exportLink(): String = outbound.exportToLink().takeIf { it.contains("://") } ?: ""

    fun exportJsonLink(strip: Boolean = false): String = outbound.exportJsonLink(strip)

    fun isChain(): Boolean = type == "chain"

    fun isEndpoint(): Boolean = outbound.isEndpoint()

    fun isXray(): Boolean = outbound.isXray()

    /** isVpnProfile (TestRunner.cpp:41-43): an OpenVPN or OpenConnect endpoint, whose tunnel can be up while a probe fails. */
    fun isVpnProfile(): Boolean = type == "openvpn" || type == "openconnect"

    /** The desktop's duplicate key (Profile.cpp:87-90): the JSON link without the tag. */
    fun dedupKey(): String = exportJsonLink(strip = true)

    fun requireChain(): Chain = outbound as? Chain ?: error("profile $id is not a chain")

    // ------------------------------------------------------------------------------------------------ test results

    /** Profile.h:64; untested profiles are neither working nor unavailable. */
    fun isWorking(): Boolean = ProxyEntity.isWorking(latency)

    fun isUnavailable(): Boolean = ProxyEntity.isUnavailable(latency)

    /** Profile::SetLatency: stamps [latencyAt], a 0 reset clears it. */
    fun recordLatency(ms: Int) {
        latency = ms
        latencyAt = if (ms == 0) 0L else System.currentTimeMillis() / 1000
    }

    fun hasTestResults(): Boolean = latency != 0 || testCountry != null || ipOut != null || dlSpeed != null ||
        ulSpeed != null || testError != null

    /** Profile::ClearTestResults, plus the Android-only error text. */
    fun clearTestResults() {
        testCountry = null
        ipOut = null
        latency = 0
        latencyAt = 0L
        dlSpeed = null
        ulSpeed = null
        testError = null
    }

    /**
     * TestRunner::applyUrlResult (TestRunner.cpp:127-142): a result is [latencyMs]; an aborted test leaves the
     * profile untested; a failed VPN profile whose tunnel is up ([connectOnly]) is [LATENCY_CONNECT_ONLY], a working
     * result that keeps no error (the desktop logs none for it); else -1.
     */
    fun applyUrlResult(latencyMs: Int, error: String, connectOnly: Boolean = false) {
        when {
            error.isEmpty() -> {
                recordLatency(latencyMs)
                testError = null
            }

            isTestAborted(error) -> {
                recordLatency(0)
                testError = null
            }

            connectOnly -> {
                recordLatency(LATENCY_CONNECT_ONLY)
                testError = null
            }

            else -> {
                recordLatency(-1)
                testError = error
            }
        }
    }

    /** TestRunner::applyIpResult (TestRunner.cpp:144-157): an error clears the egress IP and country. */
    fun applyIpResult(ip: String, country: String, error: String) {
        if (error.isEmpty()) {
            ipOut = ip
            testCountry = country
            testError = null
        } else {
            ipOut = null
            testCountry = null
            testError = if (isTestAborted(error)) null else error
        }
    }

    /**
     * The speed-test result of TestRunner.cpp:614-648: the core's rate strings, the ping only for a profile without
     * a latency, [country] (ISO code) when known; an error is "N/A", latency -1 and no country.
     */
    fun applySpeedResult(dl: String, ul: String, latencyMs: Int, country: String, error: String) {
        if (error.isEmpty()) {
            dlSpeed = dl
            ulSpeed = ul
            if (latency <= 0 && latencyMs > 0) recordLatency(latencyMs)
            if (country.isNotEmpty()) testCountry = country
            testError = null
        } else {
            dlSpeed = SPEED_NA
            ulSpeed = SPEED_NA
            recordLatency(-1)
            testCountry = null
            testError = error
        }
    }

    /** Profile::DisplayLatencyColor; 0 = no colour (untested). */
    @ColorInt
    fun latencyColor(): Int = when {
        latency == LATENCY_CONNECT_ONLY -> COLOR_CONNECT_ONLY
        latency < 0 -> COLOR_FAILED
        latency == 0 -> 0
        latency <= 100 -> COLOR_FAST
        latency <= 300 -> COLOR_MEDIUM
        else -> COLOR_SLOW
    }

    companion object {
        const val TABLE = "profiles"

        /** kLatencyConnectOnly (Profile.h:37): the egress probe failed but the VPN tunnel is up. */
        const val LATENCY_CONNECT_ONLY = -2

        /** The rate text of a failed speed test. */
        const val SPEED_NA = "N/A"

        @ColorInt private const val COLOR_CONNECT_ONLY = 0xFF00ACC1.toInt()
        @ColorInt private const val COLOR_FAILED = 0xFF9E9E9E.toInt()
        @ColorInt private const val COLOR_FAST = 0xFF43A047.toInt()
        @ColorInt private const val COLOR_MEDIUM = 0xFFF9A825.toInt()
        @ColorInt private const val COLOR_SLOW = 0xFFE53935.toInt()

        /** Profile::IsWorking for a latency code: a measured latency or a connect-only tunnel. */
        @JvmStatic
        fun isWorking(latency: Int): Boolean = latency > 0 || latency == LATENCY_CONNECT_ONLY

        /** Profile::IsUnavailable for a latency code: a failed test; connect-only counts as working. */
        @JvmStatic
        fun isUnavailable(latency: Int): Boolean = latency < 0 && latency != LATENCY_CONNECT_ONLY

        /** TestRunner.cpp:37-39. */
        @JvmStatic
        fun isTestAborted(error: String): Boolean =
            error.contains("test aborted") || error.contains("context canceled")

        /** CountryCodeToFlag: each letter shifted to its regional indicator symbol. */
        @JvmStatic
        fun countryFlag(code: String): String {
            val sb = StringBuilder()
            code.uppercase().forEach { c -> sb.appendCodePoint(c.code + 0x1F1A5) }
            return sb.toString()
        }

        /**
         * The stored (type, JSON) pair as an [Outbound]: an [InvalidOutbound] for an unknown type or unparsable
         * text; a blank or empty document yields the type's defaults (a freshly created profile).
         */
        @JvmStatic
        fun parseOutbound(type: String, json: String): Outbound {
            val outbound = OutboundFactory.newByType(type)
            if (outbound.invalid) return outbound
            val obj = if (json.isBlank()) JsonObject() else JsonInput.parseObjectOrNull(json) ?: return InvalidOutbound(type)
            if (obj.isNotEmpty()) outbound.parseFromJson(obj)
            return outbound
        }
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM `profiles`")
        fun getAll(): List<ProxyEntity>

        @Query("SELECT `id` FROM `profiles` WHERE `gid` = :groupId ORDER BY `user_order`, `id`")
        fun getIdsByGroup(groupId: Long): List<Long>

        /** The group's members in list order without their data. */
        @Query("SELECT `id`, `gid`, `type`, `name` FROM `profiles` WHERE `gid` = :groupId ORDER BY `user_order`, `id`")
        fun getNamesByGroup(groupId: Long): List<ProfileName>

        /** The lowest id whose stored name is [name]. */
        @Query("SELECT `id` FROM `profiles` WHERE `name` = :name ORDER BY `id` LIMIT 1")
        fun idByName(name: String): Long?

        /** The profiles without a stored name, by id; their display name comes from the outbound. */
        @Query("SELECT * FROM `profiles` WHERE `name` IS NULL OR `name` = '' ORDER BY `id`")
        fun getUnnamed(): List<ProxyEntity>

        /** The groups of [proxyIds] (at most 999 ids per call on old SQLite). */
        @Query("SELECT DISTINCT `gid` FROM `profiles` WHERE `id` IN (:proxyIds)")
        fun groupsOf(proxyIds: List<Long>): List<Long>

        /** Which of [proxyIds] exist (at most 999 ids per call on old SQLite). */
        @Query("SELECT `id` FROM `profiles` WHERE `id` IN (:proxyIds)")
        fun existingIds(proxyIds: List<Long>): List<Long>

        /** Which of [proxyIds] exist outside group [groupId] (at most 998 ids per call on old SQLite). */
        @Query("SELECT `id` FROM `profiles` WHERE `id` IN (:proxyIds) AND `gid` != :groupId")
        fun idsOutsideGroup(proxyIds: List<Long>, groupId: Long): List<Long>

        /** The group's members with their stored positions, in list order. */
        @Query("SELECT `id`, `user_order` FROM `profiles` WHERE `gid` = :groupId ORDER BY `user_order`, `id`")
        fun getPositionsByGroup(groupId: Long): List<ProfilePosition>

        /** The position of [proxyId] when it is a member of group [groupId]. */
        @Query("SELECT `user_order` FROM `profiles` WHERE `id` = :proxyId AND `gid` = :groupId")
        fun orderInGroup(proxyId: Long, groupId: Long): Long?

        /** Whether the group's positions are exactly 0..n-1. */
        @Query(
            "SELECT COUNT(*) = COUNT(DISTINCT `user_order`) AND COALESCE(MIN(`user_order`), 0) = 0 " +
                "AND COALESCE(MAX(`user_order`), -1) = COUNT(*) - 1 FROM `profiles` WHERE `gid` = :groupId"
        )
        fun isDense(groupId: Long): Boolean

        /** Closes the gap the row at [order] left in group [groupId]. */
        @Query("UPDATE `profiles` SET `user_order` = `user_order` - 1 WHERE `gid` = :groupId AND `user_order` > :order")
        fun closeGap(groupId: Long, order: Long)

        /**
         * The group's members without their data: `type` and `outbound_json` are empty, `name`, `test_country` and
         * `test_error` null, so only the keys that read plain columns (test results, traffic) can be taken from them.
         */
        @Query(
            "SELECT `id`, '' AS `type`, NULL AS `name`, `gid`, `user_order`, `latency`, `latency_at`, `dl_speed`, " +
                "`ul_speed`, NULL AS `test_country`, `ip_out`, '' AS `outbound_json`, `traffic_dl`, `traffic_up`, " +
                "NULL AS `test_error` FROM `profiles` WHERE `gid` = :groupId"
        )
        fun getWithoutDataByGroup(groupId: Long): List<ProxyEntity>

        /** [getWithoutDataByGroup] for [proxyIds] (at most 999 ids per call on old SQLite). */
        @Query(
            "SELECT `id`, '' AS `type`, NULL AS `name`, `gid`, `user_order`, `latency`, `latency_at`, `dl_speed`, " +
                "`ul_speed`, NULL AS `test_country`, `ip_out`, '' AS `outbound_json`, `traffic_dl`, `traffic_up`, " +
                "NULL AS `test_error` FROM `profiles` WHERE `id` IN (:proxyIds)"
        )
        fun getWithoutData(proxyIds: List<Long>): List<ProxyEntity>

        /** The group's members in list order. */
        @Query("SELECT * FROM `profiles` WHERE `gid` = :groupId ORDER BY `user_order`, `id`")
        fun getByGroup(groupId: Long): List<ProxyEntity>

        @Query("SELECT * FROM `profiles` WHERE `id` IN (:proxyIds)")
        fun getEntities(proxyIds: List<Long>): List<ProxyEntity>

        @Query("SELECT COUNT(*) FROM `profiles` WHERE `gid` = :groupId")
        fun countByGroup(groupId: Long): Long

        @Query("SELECT COALESCE(MAX(`user_order`), -1) + 1 FROM `profiles` WHERE `gid` = :groupId")
        fun nextOrder(groupId: Long): Long

        @Query("SELECT * FROM `profiles` WHERE `id` = :proxyId")
        fun getById(proxyId: Long): ProxyEntity?

        @Query("DELETE FROM `profiles` WHERE `id` = :proxyId")
        fun deleteById(proxyId: Long): Int

        @Query("DELETE FROM `profiles` WHERE `id` IN (:proxyIds)")
        fun deleteByIds(proxyIds: List<Long>): Int

        @Update
        fun updateProxy(proxy: ProxyEntity): Int

        @Update
        fun updateProxy(proxies: List<ProxyEntity>): Int

        /** Writes only a row whose order differs, so reordering a long group touches only the moved rows. */
        @Query("UPDATE `profiles` SET `user_order` = :order WHERE `id` = :proxyId AND `user_order` != :order")
        fun setUserOrder(proxyId: Long, order: Long)

        @Query("UPDATE `profiles` SET `gid` = :groupId, `user_order` = :order WHERE `id` = :proxyId")
        fun setGroup(proxyId: Long, groupId: Long, order: Long)

        /** New data for a profile in place (subscription update, resolved address): test results and traffic stay. */
        @Query("UPDATE `profiles` SET `type` = :type, `name` = :name, `outbound_json` = :outboundJson WHERE `id` = :proxyId")
        fun updateOutbound(proxyId: Long, type: String, name: String?, outboundJson: String): Int

        @Query("UPDATE `profiles` SET `traffic_dl` = :rx, `traffic_up` = :tx WHERE `id` = :proxyId")
        fun updateTraffic(proxyId: Long, rx: Long, tx: Long): Int

        @Query("UPDATE `profiles` SET `traffic_dl` = `traffic_dl` + :rx, `traffic_up` = `traffic_up` + :tx WHERE `id` = :proxyId")
        fun addTraffic(proxyId: Long, rx: Long, tx: Long): Int

        @Query("UPDATE `profiles` SET `traffic_dl` = 0, `traffic_up` = 0 WHERE `id` IN (:profileIds)")
        fun resetTraffic(profileIds: LongArray): Int

        @Query(
            "UPDATE `profiles` SET `latency` = :latency, `latency_at` = :latencyAt, `dl_speed` = :dlSpeed, " +
                "`ul_speed` = :ulSpeed, `test_country` = :testCountry, `ip_out` = :ipOut, `test_error` = :testError " +
                "WHERE `id` = :proxyId"
        )
        fun updateTestResult(
            proxyId: Long,
            latency: Int,
            latencyAt: Long,
            dlSpeed: String?,
            ulSpeed: String?,
            testCountry: String?,
            ipOut: String?,
            testError: String?,
        ): Int

        /** A latency result alone, written only when [latency] differs from the stored one. */
        @Query(
            "UPDATE `profiles` SET `latency` = :latency, `latency_at` = :latencyAt, `test_error` = :testError " +
                "WHERE `id` = :proxyId AND `latency` != :latency"
        )
        fun updateLatency(proxyId: Long, latency: Int, latencyAt: Long, testError: String?): Int

        @Query(
            "UPDATE `profiles` SET `latency` = 0, `latency_at` = 0, `dl_speed` = NULL, `ul_speed` = NULL, " +
                "`test_country` = NULL, `ip_out` = NULL, `test_error` = NULL WHERE `id` IN (:profileIds)"
        )
        fun clearTestResults(profileIds: List<Long>): Int

        @Query(
            "UPDATE `profiles` SET `latency` = 0, `latency_at` = 0, `dl_speed` = NULL, `ul_speed` = NULL, " +
                "`test_country` = NULL, `ip_out` = NULL, `test_error` = NULL WHERE `gid` = :groupId"
        )
        fun clearGroupTestResults(groupId: Long): Int

        @Insert
        fun addProxy(proxy: ProxyEntity): Long

        @Insert
        fun insert(proxies: List<ProxyEntity>): List<Long>

        @Query("DELETE FROM `profiles`")
        fun reset()
    }
}

/** A profile row without its data: list order, names and types. */
data class ProfileName(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "gid") val groupId: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "name") val name: String?,
)

/** A profile's stored position in its group. */
data class ProfilePosition(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "user_order") val userOrder: Long,
)
