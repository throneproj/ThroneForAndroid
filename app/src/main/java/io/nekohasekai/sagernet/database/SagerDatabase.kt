package io.nekohasekai.sagernet.database

import android.util.Log
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.preference.SettingEntry
import java.io.File
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Database(
    entities = [
        ProxyGroup::class, ProxyEntity::class, RouteProfileEntity::class, RouteRuleEntity::class,
        DnsServerEntity::class,
        SettingEntry::class, MarkerEntity::class,
    ],
    version = 13,
    autoMigrations = [
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 12, to = 13),
    ]
)
@TypeConverters(value = [SubscriptionOptions.Converter::class])
abstract class SagerDatabase : RoomDatabase() {

    companion object {

        private const val TAG = "SagerDatabase"

        /** The settings database of v11 and older; its rows are not carried over. */
        private const val LEGACY_SETTINGS_DB = "configuration.db"

        /** 9 -> 10: the profile table is rebuilt around (type, outboundJson); groups, rules and settings are untouched. */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `proxy_entities`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `proxy_entities` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`groupId` INTEGER NOT NULL, `type` TEXT NOT NULL, `outboundJson` TEXT NOT NULL, " +
                        "`userOrder` INTEGER NOT NULL, `tx` INTEGER NOT NULL, `rx` INTEGER NOT NULL, " +
                        "`status` INTEGER NOT NULL, `ping` INTEGER NOT NULL, `uuid` TEXT NOT NULL, `error` TEXT, " +
                        "`speedTestMode` TEXT NOT NULL DEFAULT '', " +
                        "`speedTestDownloadBitsPerSecond` INTEGER NOT NULL DEFAULT 0, " +
                        "`speedTestUploadBitsPerSecond` INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `groupId` ON `proxy_entities` (`groupId`)")
            }
        }

        /** 10 -> 11: the flat `rules` table gives way to the desktop's route profiles, seeded with the Default one. */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `rules`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `route_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL DEFAULT '', `default_outbound_id` INTEGER NOT NULL DEFAULT -1, " +
                        "`is_remote` INTEGER NOT NULL DEFAULT 0, `remote_url` TEXT NOT NULL DEFAULT '', " +
                        "`auto_update` INTEGER NOT NULL DEFAULT 0, `remote_last_update` INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `route_rules` (`route_profile_id` INTEGER NOT NULL, " +
                        "`rule_order` INTEGER NOT NULL, `name` TEXT NOT NULL DEFAULT '', `type` INTEGER NOT NULL DEFAULT 0, " +
                        "`ip_version` TEXT NOT NULL DEFAULT '', `network` TEXT NOT NULL DEFAULT '', " +
                        "`protocol` TEXT NOT NULL DEFAULT '', `inbound_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`domain_json` TEXT NOT NULL DEFAULT '[]', `domain_suffix_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`domain_keyword_json` TEXT NOT NULL DEFAULT '[]', `domain_regex_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`source_ip_cidr_json` TEXT NOT NULL DEFAULT '[]', `source_ip_is_private` INTEGER NOT NULL DEFAULT 0, " +
                        "`ip_cidr_json` TEXT NOT NULL DEFAULT '[]', `ip_is_private` INTEGER NOT NULL DEFAULT 0, " +
                        "`source_port_json` TEXT NOT NULL DEFAULT '[]', `source_port_range_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`port_json` TEXT NOT NULL DEFAULT '[]', `port_range_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`process_name_json` TEXT NOT NULL DEFAULT '[]', `process_path_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`process_path_regex_json` TEXT NOT NULL DEFAULT '[]', `package_name_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`rule_set_json` TEXT NOT NULL DEFAULT '[]', `invert` INTEGER NOT NULL DEFAULT 0, " +
                        "`outbound_id` INTEGER NOT NULL DEFAULT -2, `action` TEXT NOT NULL DEFAULT 'route', " +
                        "`reject_method` TEXT NOT NULL DEFAULT '', `no_drop` INTEGER NOT NULL DEFAULT 0, " +
                        "`override_address` TEXT NOT NULL DEFAULT '', `override_port` TEXT NOT NULL DEFAULT '', " +
                        "`sniffers_json` TEXT NOT NULL DEFAULT '[]', `sniff_override_dest` INTEGER NOT NULL DEFAULT 0, " +
                        "`strategy` TEXT NOT NULL DEFAULT '', `wifi_ssid_json` TEXT NOT NULL DEFAULT '[]', " +
                        "`wifi_bssid_json` TEXT NOT NULL DEFAULT '[]', `tls_spoof` TEXT NOT NULL DEFAULT '', " +
                        "`tls_spoof_method` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`route_profile_id`, `rule_order`), " +
                        "FOREIGN KEY(`route_profile_id`) REFERENCES `route_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "INSERT INTO `route_profiles` (`id`, `name`, `default_outbound_id`) VALUES (1, 'Default', -1)"
                )
                db.execSQL(
                    "INSERT INTO `route_rules` (`route_profile_id`, `rule_order`, `name`, `protocol`, `action`) " +
                        "VALUES (1, 0, 'Route DNS', 'dns', 'hijack-dns')"
                )
            }
        }

        /** 12 -> 13: add lxbox/asteriskbox extension columns to route_rules. */
        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `route_rules` ADD COLUMN `logical_mode` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `route_rules` ADD COLUMN `rules_json` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `route_rules` ADD COLUMN `default_interface_address_json` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `route_rules` ADD COLUMN `dns_server_json` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `route_rules` ADD COLUMN `balancer_mode` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `route_rules` ADD COLUMN `balancer_pool` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `route_rules` ADD COLUMN `balancer_pool_tolerance` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `route_rules` ADD COLUMN `balancer_sticky_hash_json` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * 11 -> 12: the desktop's `groups` / `profiles` tables replace `proxy_groups` / `proxy_entities` (no data is
         * carried over), `settings` moves in from configuration.db, `markers` is added and `route_profiles` gains the
         * desktop's raw and endpoint columns. The Default group is created as on a fresh install.
         */
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `proxy_entities`")
                db.execSQL("DROP TABLE IF EXISTS `proxy_groups`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`archive` INTEGER NOT NULL DEFAULT 0, `skip_auto_update` INTEGER NOT NULL DEFAULT 0, " +
                        "`name` TEXT NOT NULL DEFAULT '', `url` TEXT NOT NULL DEFAULT '', `info` TEXT NOT NULL DEFAULT '', " +
                        "`sub_last_update` INTEGER NOT NULL DEFAULT 0, `front_proxy_id` INTEGER NOT NULL DEFAULT -1, " +
                        "`landing_proxy_id` INTEGER NOT NULL DEFAULT -1, `column_width_json` TEXT NOT NULL DEFAULT '', " +
                        "`scroll_last_profile` INTEGER NOT NULL DEFAULT -1, " +
                        "`auto_clear_unavailable` INTEGER NOT NULL DEFAULT 0, `test_sort_by` INTEGER NOT NULL DEFAULT 0, " +
                        "`traffic_sort_by` INTEGER NOT NULL DEFAULT 0, `test_items_to_show` INTEGER NOT NULL DEFAULT 0, " +
                        "`type_sort_by` INTEGER NOT NULL DEFAULT 0, `sub_options_json` TEXT NOT NULL DEFAULT '{}', " +
                        "`display_order` INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, `name` TEXT, `gid` INTEGER NOT NULL DEFAULT 0, " +
                        "`user_order` INTEGER NOT NULL DEFAULT 0, `latency` INTEGER NOT NULL DEFAULT 0, " +
                        "`latency_at` INTEGER NOT NULL DEFAULT 0, `dl_speed` TEXT, `ul_speed` TEXT, `test_country` TEXT, " +
                        "`ip_out` TEXT, `outbound_json` TEXT NOT NULL, `traffic_dl` INTEGER NOT NULL DEFAULT 0, " +
                        "`traffic_up` INTEGER NOT NULL DEFAULT 0, `test_error` TEXT, " +
                        "FOREIGN KEY(`gid`) REFERENCES `groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_profiles_gid` ON `profiles` (`gid`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `markers` (`key` TEXT NOT NULL, " +
                        "`marked_at` INTEGER NOT NULL DEFAULT (strftime('%s','now')), PRIMARY KEY(`key`))"
                )
                db.execSQL("ALTER TABLE `route_profiles` ADD COLUMN `is_raw` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `route_profiles` ADD COLUMN `raw_route` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `route_profiles` ADD COLUMN `prevent_modifications` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `route_profiles` ADD COLUMN `endpoint_profile_ids` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `route_profiles` ADD COLUMN `inner_hop_endpoint_ids` TEXT NOT NULL DEFAULT '[]'")
                insertDefaultGroup(db)
            }
        }

        /** Configs.cpp:35-39: a new database starts with an ordinary group named "Default". */
        private val callback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                insertDefaultGroup(db)
            }
        }

        private fun insertDefaultGroup(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT INTO `groups` (`name`, `display_order`) SELECT ?, 1 WHERE NOT EXISTS (SELECT 1 FROM `groups`)",
                arrayOf<Any?>(defaultGroupName())
            )
        }

        fun defaultGroupName(): String = SagerNet.application.getString(R.string.group_default_name)

        @OptIn(DelicateCoroutinesApi::class)
        private fun buildProfileDatabase(): SagerDatabase =
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
                .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                .addCallback(callback)
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
                .setQueryExecutor { GlobalScope.launch { it.run() } }
                .build()

        // Failures are logged with android.util.Log: Logs reads the log level from the settings in this database.
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            val db = buildProfileDatabase()
            // A corrupted file throws on first access: keep a copy, rebuild an empty database, keep the app starting.
            val opened = try {
                db.openHelper.writableDatabase
                db
            } catch (e: Exception) {
                Log.e(TAG, "open failed", e)
                runCatching { db.close() }
                backupCorruptedDatabase()
                SagerNet.application.deleteDatabase(Key.DB_PROFILE)
                buildProfileDatabase()
            }
            SagerNet.application.deleteDatabase(LEGACY_SETTINGS_DB)
            opened
        }

        /** Copies the database file next to itself with a timestamp before it is deleted and rebuilt. */
        private fun backupCorruptedDatabase() {
            runCatching {
                val dbFile = SagerNet.application.getDatabasePath(Key.DB_PROFILE)
                if (dbFile.exists()) {
                    val backupFile = File(
                        dbFile.parentFile, dbFile.name + ".bak_" + System.currentTimeMillis()
                    )
                    dbFile.copyTo(backupFile, overwrite = false)
                    Log.i(TAG, "Corrupted database backed up as ${backupFile.name}")
                }
            }.onFailure {
                Log.w(TAG, "Failed to backup corrupted database before rebuild", it)
            }
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val routeDao get() = instance.routeDao()
        val settingsDao get() = instance.settingsDao()
        val markerDao get() = instance.markerDao()
        val dnsServerDao get() = instance.dnsServerDao()

    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun routeDao(): RouteDao
    abstract fun settingsDao(): SettingEntry.Dao
    abstract fun markerDao(): MarkerEntity.Dao
    abstract fun dnsServerDao(): DnsServerDao

}