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
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonValues
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
    /** [TestShowItems], the mirror of [visibleColumns] that older readers use; written with [updateVisibleColumns]. */
    @ColumnInfo(name = "test_items_to_show", defaultValue = "0") var testItemsToShow: Int = TestShowItems.NONE.value,
    /** [TypeBy] */
    @ColumnInfo(name = "type_sort_by", defaultValue = "0") var typeSortBy: Int = 0,
    @ColumnInfo(name = "sub_options_json", defaultValue = "{}") var subOptions: SubscriptionOptions = SubscriptionOptions(),
    /** The last fetch's [SubUserInfo]; read it through [subInfo]. */
    @ColumnInfo(name = "sub_metadata_json", defaultValue = "{}") var subMetadata: SubUserInfo = SubUserInfo(),
    /** The [GroupSortMethod] ordinal the group is kept sorted by; read both through [sort]. */
    @ColumnInfo(name = "sort_method", defaultValue = "0") var sortMethod: Int = 0,
    @ColumnInfo(name = "sort_descending", defaultValue = "0") var sortDescending: Boolean = false,
    @ColumnInfo(name = "display_order", defaultValue = "0") var displayOrder: Long = 0L,
    /** The routing profile this group's profiles connect with; -1 (or a profile that is gone) follows the global one (Group.h). */
    @ColumnInfo(name = "route_id", defaultValue = "-1") var routeId: Long = -1L,
    /** Subscription refreshes save the routing profile the subscription provides as this group's own (Group.h). */
    @ColumnInfo(name = "import_sub_route", defaultValue = "0") var importSubRoute: Boolean = false,
    /**
     * Group::pinned as its compact JSON array; read it through [pinned]. Written only by ProfileManager's pin edits,
     * so [GroupRepo.save] keeps the stored value.
     */
    @ColumnInfo(name = "pinned_json", defaultValue = "[]") var pinnedJson: String = "[]",
    /** Group::show_services: the rows show the service checks' results (1c4d920a). */
    @ColumnInfo(name = "show_services", defaultValue = "1") var showServices: Boolean = true,
    /** Group::endpoint, the EndpointSource JSON: `{}`, `{"mode":"own"}`, an address or an IP list. */
    @ColumnInfo(name = "endpoint_json", defaultValue = "{}") var endpointJson: String = "{}",
    /** Group::visible_columns, bits of [VisibleColumns]; change it with [updateVisibleColumns]. */
    @ColumnInfo(name = "visible_columns", defaultValue = "79") var visibleColumns: Int = VisibleColumns.DEFAULT,
    /**
     * Group::custom_order as its compact JSON array; read it through [customOrder]. Written only through
     * [Dao.setCustomOrderJson], so [GroupRepo.save] keeps the stored value.
     */
    @ColumnInfo(name = "custom_order_json", defaultValue = "[]") var customOrderJson: String = "[]",
) : Parcelable {

    @get:Ignore
    val isSubscription: Boolean get() = url.isNotEmpty()

    /** Group::pinned: always the head of the group's order, in this order; sorts only order the rows below them. */
    @get:Ignore
    val pinned: List<Long> get() = parsePinned(pinnedJson)

    /**
     * Group::custom_order: the manual arrangement of the members, pinned ones included. An unsorted group lists it
     * first and the members it lacks after it; a kept sort saves the arrangement here to go back to.
     */
    @get:Ignore
    val customOrder: List<Long> get() = parsePinned(customOrderJson)

    /** Sets [visibleColumns] and keeps test_items_to_show showing the same exit IP and speed columns. */
    fun updateVisibleColumns(bits: Int) {
        visibleColumns = bits
        testItemsToShow = testItemsFor(bits)
    }

    /** Group::sort, the order kept as the profiles change; RAW keeps the manual order (GroupsRepo.cpp groupFromJson). */
    @get:Ignore
    val sort: GroupSortAction
        get() {
            val method = GroupSortMethod.of(sortMethod)
            return GroupSortAction(method, method != GroupSortMethod.RAW && sortDescending)
        }

    /** Group::SortReads: whether a change of [input] can move a row of the kept sort. */
    fun sortReads(input: SortInput): Boolean = GroupSort.reads(sort.method, input)

    /** Group::KeepsSorted. */
    fun keepsSorted(): Boolean = sortReads(SortInput.MEMBERSHIP)

    /** GroupsRepo.cpp:110-112: rows written before `sub_metadata_json` keep the raw header in [info]. */
    @get:Ignore
    val subInfo: SubUserInfo
        get() = if (subMetadata.valid || info.isEmpty()) subMetadata else SubUserInfo.parseHeader(info)

    fun displayName(): String = name.takeIf { it.isNotBlank() } ?: app.getString(R.string.group_default_name)

    companion object {
        const val TABLE = "groups"

        const val NO_PINS = "[]"

        /** QJsonArray2QListInt over `pinned_json`: ids in order, duplicates and non-ids dropped. */
        @JvmStatic
        fun parsePinned(text: String?): List<Long> {
            if (text.isNullOrBlank() || text == NO_PINS) return emptyList()
            val array = JsonInput.parseValue(text) as? JsonArray ?: return emptyList()
            val ids = LinkedHashSet<Long>()
            for (item in array) JsonValues.toInteger(item).takeIf { it > 0L }?.let(ids::add)
            return ids.toList()
        }

        @JvmStatic
        fun pinnedToJson(ids: List<Long>): String {
            if (ids.isEmpty()) return NO_PINS
            val array = JsonArray()
            ids.forEach { array.add(it) }
            return array.toCompact()
        }

        @JvmStatic
        fun customOrderToJson(ids: List<Long>): String = pinnedToJson(ids)

        /** The test_items_to_show that shows the exit IP and speed columns of [bits]. */
        @JvmStatic
        fun testItemsFor(bits: Int): Int {
            val ip = (bits and VisibleColumns.EXIT_IP) != 0
            val speed = (bits and VisibleColumns.SPEED) != 0
            return when {
                ip && speed -> TestShowItems.ALL
                ip -> TestShowItems.IP_ONLY
                speed -> TestShowItems.SPEED_ONLY
                else -> TestShowItems.NONE
            }.value
        }

        /** GroupsRepo.cpp's visible_columns migration: the columns [testItemsToShow] showed (unknown values showed all). */
        @JvmStatic
        fun visibleColumnsFor(testItemsToShow: Int): Int = when (TestShowItems.of(testItemsToShow)) {
            TestShowItems.ALL -> VisibleColumns.ALL
            TestShowItems.NONE -> VisibleColumns.DEFAULT
            TestShowItems.IP_ONLY -> VisibleColumns.DEFAULT or VisibleColumns.EXIT_IP
            TestShowItems.SPEED_ONLY -> VisibleColumns.DEFAULT or VisibleColumns.SPEED
        }
    }

    /** ProfilesTableModel::Column bits of Group::visible_columns. */
    object VisibleColumns {
        const val TYPE = 1
        const val ADDRESS = 2
        const val NAME = 4
        const val LATENCY = 8
        const val EXIT_IP = 16
        const val SPEED = 32
        const val TRAFFIC = 64

        /** Type, address, name, latency and traffic. */
        const val DEFAULT = 79
        const val ALL = 127
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

        /**
         * Configs.cpp:35-39 initDB: the Default group of an empty table, in one statement so both processes may call
         * it; test_items_to_show 1 (NONE) mirrors the default visible_columns.
         */
        @Query(
            "INSERT INTO `groups` (`name`, `display_order`, `test_items_to_show`) SELECT :name, 1, 1 " +
                "WHERE NOT EXISTS (SELECT 1 FROM `groups`)"
        )
        fun insertDefaultIfEmpty(name: String)

        @Insert
        fun insert(group: ProxyGroup): Long

        @Insert
        fun insert(groups: List<ProxyGroup>)

        @Update
        fun update(group: ProxyGroup): Int

        @Query("UPDATE `groups` SET `display_order` = :order WHERE `id` = :groupId")
        fun setDisplayOrder(groupId: Long, order: Long)

        /** Group::SetSort without rewriting the rest of the row; 0 when nothing changed. */
        @Query(
            "UPDATE `groups` SET `sort_method` = :method, `sort_descending` = :descending " +
                "WHERE `id` = :groupId AND (`sort_method` != :method OR `sort_descending` != :descending)"
        )
        fun setSort(groupId: Long, method: Int, descending: Boolean): Int

        /**
         * A subscription refresh's stamp without rewriting the rest of the row; the structured info replaces the raw
         * header (GroupUpdater.cpp:757-759).
         */
        @Query("UPDATE `groups` SET `sub_last_update` = :lastUpdate, `info` = '', `sub_metadata_json` = :metadata WHERE `id` = :groupId")
        fun setSubscriptionInfo(groupId: Long, lastUpdate: Long, metadata: String): Int

        /** An answer without profiles still keeps what it said, e.g. why (GroupUpdater.cpp:746-752); no update time. */
        @Query("UPDATE `groups` SET `info` = '', `sub_metadata_json` = :metadata WHERE `id` = :groupId")
        fun setSubscriptionMetadata(groupId: Long, metadata: String): Int

        @Query("UPDATE `groups` SET `route_id` = :routeId WHERE `id` = :groupId")
        fun setRouteId(groupId: Long, routeId: Long): Int

        @Query("UPDATE `groups` SET `import_sub_route` = 0 WHERE `id` = :groupId")
        fun stopRouteImport(groupId: Long): Int

        @Query("SELECT `pinned_json` FROM `groups` WHERE `id` = :groupId")
        fun pinnedJson(groupId: Long): String?

        /** 0 when nothing changed. */
        @Query("UPDATE `groups` SET `pinned_json` = :json WHERE `id` = :groupId AND `pinned_json` != :json")
        fun setPinnedJson(groupId: Long, json: String): Int

        @Query("SELECT `id` FROM `groups` WHERE `pinned_json` != '[]'")
        fun idsWithPins(): List<Long>

        @Query("SELECT `custom_order_json` FROM `groups` WHERE `id` = :groupId")
        fun getCustomOrderJson(groupId: Long): String?

        /** 0 when nothing changed. */
        @Query("UPDATE `groups` SET `custom_order_json` = :json WHERE `id` = :groupId AND `custom_order_json` != :json")
        fun setCustomOrderJson(groupId: Long, json: String): Int

        /** ClearHopReferences, only while the front proxy is still [profileId]; 0 when nothing changed. */
        @Query("UPDATE `groups` SET `front_proxy_id` = -1 WHERE `id` = :groupId AND `front_proxy_id` = :profileId")
        fun clearFrontProxy(groupId: Long, profileId: Long): Int

        @Query("UPDATE `groups` SET `landing_proxy_id` = -1 WHERE `id` = :groupId AND `landing_proxy_id` = :profileId")
        fun clearLandingProxy(groupId: Long, profileId: Long): Int

        /** The group's profiles go by the foreign key cascade. */
        @Query("DELETE FROM `groups` WHERE `id` = :groupId")
        fun deleteById(groupId: Long): Int

        @Query("DELETE FROM `groups`")
        fun reset()
    }
}
