package io.nekohasekai.sagernet.database

import androidx.room.*

@Dao
interface DnsServerDao {
    @Query("SELECT * FROM dns_servers ORDER BY user_order ASC, id ASC")
    suspend fun list(): List<DnsServerEntity>

    @Query("SELECT * FROM dns_servers WHERE id = :id")
    suspend fun load(id: Long): DnsServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: DnsServerEntity): Long

    @Update
    suspend fun save(entity: List<DnsServerEntity>)

    @Query("DELETE FROM dns_servers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM dns_servers")
    suspend fun deleteAll()
}
