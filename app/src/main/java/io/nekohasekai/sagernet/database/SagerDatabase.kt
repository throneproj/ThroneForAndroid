package io.nekohasekai.sagernet.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.matrix.roomigrant.GenerateRoomMigrations
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.fmt.gson.GsonConverters
import java.io.File
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 9,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
    ]
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
@GenerateRoomMigrations
abstract class SagerDatabase : RoomDatabase() {

    companion object {

        private fun buildProfileDatabase(): SagerDatabase =
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
//                .addMigrations(*SagerDatabase_Migrations.build())
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
                .setQueryExecutor { GlobalScope.launch { it.run() } }
                .build()

        @OptIn(DelicateCoroutinesApi::class)
        @Suppress("EXPERIMENTAL_API_USAGE")
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            val db = buildProfileDatabase()
            // 先试打开：数据库文件损坏时首次访问会抛异常，此时记录原始错误、
            // 删除损坏文件并重建空库，让应用可以继续启动而不是陷入崩溃循环。
            try {
                db.openHelper.writableDatabase
            } catch (e: Exception) {
                Logs.e(e)
                runCatching { db.close() }
                backupCorruptedDatabase()
                SagerNet.application.deleteDatabase(Key.DB_PROFILE)
                return@lazy buildProfileDatabase()
            }
            db
        }

        /**
         * 删库重建前将原库文件备份为同目录下带时间戳的副本，降低数据丢失面。
         * 备份失败仅记录日志，不阻断删库重建流程。
         */
        private fun backupCorruptedDatabase() {
            runCatching {
                val dbFile = SagerNet.application.getDatabasePath(Key.DB_PROFILE)
                if (dbFile.exists()) {
                    val backupFile = File(
                        dbFile.parentFile, dbFile.name + ".bak_" + System.currentTimeMillis()
                    )
                    dbFile.copyTo(backupFile, overwrite = false)
                    Logs.i("Corrupted database backed up as ${backupFile.name}")
                }
            }.onFailure {
                Logs.w("Failed to backup corrupted database before rebuild", it)
            }
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()

    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao

}
