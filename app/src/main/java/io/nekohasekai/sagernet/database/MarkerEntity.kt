package io.nekohasekai.sagernet.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** The desktop's `markers(key, marked_at)` table (MarkersRepo.cpp:8-15): one-time flags, stored opaque. */
@Entity(tableName = MarkerEntity.TABLE)
data class MarkerEntity(
    @PrimaryKey var key: String = "",
    /** Epoch seconds. */
    @ColumnInfo(name = "marked_at", defaultValue = "(strftime('%s','now'))") var markedAt: Long = 0L,
) {

    companion object {
        const val TABLE = "markers"

        /** Set once the IPv6 private ranges are in vpn_private_ranges (DatabaseManager.cpp:130-146). */
        const val TUN_PRIVATE_RANGES_IPV6 = "migration.tun_private_ranges_ipv6"

        /** Set once simple_dl_url's old 1mb default has moved to 5mb (DatabaseManager.cpp applyMigrations). */
        const val SIMPLE_DL_URL_5MB = "migration.simple_dl_url_5mb"
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM `markers` ORDER BY `key`")
        fun all(): List<MarkerEntity>

        @Query("SELECT * FROM `markers` WHERE `key` = :key")
        fun get(key: String): MarkerEntity?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun put(marker: MarkerEntity)

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun putAll(markers: List<MarkerEntity>)

        @Query("DELETE FROM `markers`")
        fun reset()
    }
}
