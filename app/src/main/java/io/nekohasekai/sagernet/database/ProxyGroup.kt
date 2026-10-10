package io.nekohasekai.sagernet.database

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import kotlinx.parcelize.Parcelize

/**
 * The desktop's `groups` row (GroupsRepo.cpp:20-43) without `profiles_json` (membership and order live in
 * `profiles.gid` / `profiles.user_order`) and with `display_order` (the desktop's `groups_order` table flattened).
 * A group is a subscription iff [url] is not empty. [columnWidthJson] is desktop table state, stored opaque.
 */
@Entity(tableName = ProxyGroup.TABLE)
@Parcelize
data class ProxyGroup(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    @ColumnInfo(name = "archive", defaultValue = "0") var archive: Boolean = false,
    @ColumnInfo(name = "skip_auto_update", defaultValue = "0") var skipAutoUpdate: Boolean = false,
    @ColumnInfo(name = "name", defaultValue = "") var name: String = "",
    @ColumnInfo(name = "url", defaultValue = "") var url: String = "",
    /** The raw Subscription-UserInfo header of fetches before [subMetadata]; refreshes clear it. */
    @ColumnInfo(name = "info", defaultValue = "") var info: String = "",
    /** Epoch seconds. */
    @ColumnInfo(name = "sub_last_update", defaultValue = "0") var subLastUpdate: Long = 0L,
    /** Profile ids; -1 (or any id <= 0) = none. */
    @ColumnInfo(name = "front_proxy_id", defaultValue = "-1") var frontProxyId: Long = -1L,
    @ColumnInfo(name = "landing_proxy_id", defaultValue = "-1") var landingProxyId: Long = -1L,
    @ColumnInfo(name = "column_width_json", defaultValue = "") var columnWidthJson: String = "",
    /** The first visible row of the list, -1 = top. */
    @ColumnInfo(name = "scroll_last_profile", defaultValue = "-1") var scrollLastProfile: Int = -1,
    @ColumnInfo(name = "auto_clear_unavailable", defaultValue = "0") var autoClearUnavailable: Boolean = false,
    /** [TestBy] */
    @ColumnInfo(name = "test_sort_by", defaultValue = "0") var testSortBy: Int = 0,
    /** [TrafficBy] */
    @ColumnInfo(name = "traffic_sort_by", defaultValue = "0") var trafficSortBy: Int = 0,
    /** [TestShowItems] */
    @ColumnInfo(name = "test_items_to_show", defaultValue = "0") var testItemsToShow: Int = 0,
    /** [TypeBy] */
    @ColumnInfo(name = "type_sort_by", defaultValue = "0") var typeSortBy: Int = 0,
    @ColumnInfo(name = "sub_options_json", defaultValue = "{}") var subOptions: SubscriptionOptions = SubscriptionOptions(),
    /** The last fetch's [SubUserInfo]; read it through [subInfo]. */
    @ColumnInfo(name = "sub_metadata_json", defaultValue = "{}") var subMetadata: SubUserInfo = SubUserInfo(),
    @ColumnInfo(name = "display_order", defaultValue = "0") var displayOrder: Long = 0L,
) : Parcelable {

    @get:Ignore
    val isSubscription: Boolean get() = url.isNotEmpty()

    /** GroupsRepo.cpp:110-112: rows written before `sub_metadata_json` keep the raw header in [info]. */
    @get:Ignore
    val subInfo: SubUserInfo
        get() = if (subMetadata.valid || info.isEmpty()) subMetadata else SubUserInfo.parseHeader(info)

    fun displayName(): String = name.takeIf { it.isNotBlank() } ?: app.getString(R.string.group_default_name)

    companion object {
        const val TABLE = "groups"
    }

    @androidx.room.Dao
    interface Dao {

        /** Tab / list order. */
        @Query("SELECT * FROM `groups` ORDER BY `display_order`, `id`")
        fun allGroups(): List<ProxyGroup>

        @Query("SELECT `id` FROM `groups` ORDER BY `display_order`, `id`")
        fun allIds(): List<Long>

        @Query("SELECT * FROM `groups` WHERE `id` = :groupId")
        fun getById(groupId: Long): ProxyGroup?

        @Query("SELECT COUNT(*) FROM `groups`")
        fun count(): Long

        /** GroupsRepo::AddGroup: MAX + 1, and 1 for the first group. */
        @Query("SELECT COALESCE(MAX(`display_order`), 0) + 1 FROM `groups`")
        fun nextDisplayOrder(): Long

        /** Configs.cpp:35-39 initDB: the Default group of an empty table, in one statement so both processes may call it. */
        @Query("INSERT INTO `groups` (`name`, `display_order`) SELECT :name, 1 WHERE NOT EXISTS (SELECT 1 FROM `groups`)")
        fun insertDefaultIfEmpty(name: String)

        @Insert
        fun insert(group: ProxyGroup): Long

        @Insert
        fun insert(groups: List<ProxyGroup>)

        @Update
        fun update(group: ProxyGroup): Int

        @Query("UPDATE `groups` SET `display_order` = :order WHERE `id` = :groupId")
        fun setDisplayOrder(groupId: Long, order: Long)

        /**
         * A subscription refresh's stamp without rewriting the rest of the row; the structured info replaces the raw
         * header (GroupUpdater.cpp:757-759).
         */
        @Query("UPDATE `groups` SET `sub_last_update` = :lastUpdate, `info` = '', `sub_metadata_json` = :metadata WHERE `id` = :groupId")
        fun setSubscriptionInfo(groupId: Long, lastUpdate: Long, metadata: String): Int

        /** An answer without profiles still keeps what it said, e.g. why (GroupUpdater.cpp:746-752); no update time. */
        @Query("UPDATE `groups` SET `info` = '', `sub_metadata_json` = :metadata WHERE `id` = :groupId")
        fun setSubscriptionMetadata(groupId: Long, metadata: String): Int

        /** The group's profiles go by the foreign key cascade. */
        @Query("DELETE FROM `groups` WHERE `id` = :groupId")
        fun deleteById(groupId: Long): Int

        @Query("DELETE FROM `groups`")
        fun reset()
    }
}
