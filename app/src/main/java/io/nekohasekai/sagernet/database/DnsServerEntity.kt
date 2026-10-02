package io.nekohasekai.sagernet.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Custom DNS server entries. Users create these and reference them by tag in route rules' dns_server field.
 */
@Entity(tableName = "dns_servers")
data class DnsServerEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") var id: Long = 0,
    @ColumnInfo(name = "tag") var tag: String = "",
    @ColumnInfo(name = "address") var address: String = "",
    @ColumnInfo(name = "type") var type: String = "udp",  // udp, tcp, https, doh, gate
    @ColumnInfo(name = "bind_interface") var bindInterface: String = "",
    @ColumnInfo(name = "detour") var detour: String = "",
    @ColumnInfo(name = "disable_cache") var disableCache: Boolean = false,
    @ColumnInfo(name = "disable_expire") var disableExpire: Boolean = false,
    @ColumnInfo(name = "ip_is_private") var ipIsPrivate: Boolean = false,
    @ColumnInfo(name = "client_subnet") var clientSubnet: String = "",
    @ColumnInfo(name = "reject_expired") var rejectExpired: Boolean = true,
    @ColumnInfo(name = "user_order") var userOrder: Int = 0,
)
