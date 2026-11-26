package io.nekohasekai.sagernet.database.backup

/**
 * The tables of a fresh desktop `throne.db` (Throne @ af4f1599), created in DatabaseManager.cpp order: `entity_ids`
 * (DatabaseManager.cpp createEntityIdsTable), ProfilesRepo.cpp, GroupsRepo.cpp, RoutesRepo.cpp, OtpProfilesRepo.cpp,
 * SettingsRepo.cpp, MarkersRepo.cpp, IpListsRepo.cpp, IpScansRepo.cpp. Update together with the desktop.
 * `groups` keeps the `test_items_to_show` the desktop dropped, as the mirror of `visible_columns` older Android builds
 * read; `route_rules` ends with Android's own rule columns, which only an Android restore reads.
 */
object DesktopSchema {

    const val ENTITY_IDS = "entity_ids"
    const val PROFILES = "profiles"
    const val GROUPS = "groups"
    const val GROUPS_ORDER = "groups_order"
    const val ROUTE_PROFILES = "route_profiles"
    const val ROUTE_RULES = "route_rules"
    const val SETTINGS = "settings"
    const val MARKERS = "markers"
    const val IP_LISTS = "ip_lists"
    const val IP_LIST_ENTRIES = "ip_list_entries"
    const val IP_SCANS = "ip_scans"

    /** remember_id's "no profile" (Const.hpp NoProfileId). */
    const val NO_PROFILE_ID = -1919L

    val DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS entity_ids (
            profile_last_id INTEGER NOT NULL DEFAULT 0,
            group_last_id INTEGER NOT NULL DEFAULT 0,
            route_profile_last_id INTEGER NOT NULL DEFAULT 0,
            otp_profile_last_id INTEGER NOT NULL DEFAULT 0,
            ip_list_last_id INTEGER NOT NULL DEFAULT 0,
            ip_scan_last_id INTEGER NOT NULL DEFAULT 0
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS profiles (
            id INTEGER PRIMARY KEY,
            type TEXT NOT NULL,
            name TEXT,
            gid INTEGER NOT NULL DEFAULT 0,
            latency INTEGER NOT NULL DEFAULT 0,
            dl_speed TEXT,
            ul_speed TEXT,
            test_country TEXT,
            ip_out TEXT,
            outbound_json TEXT NOT NULL,
            traffic_dl INTEGER NOT NULL DEFAULT 0,
            traffic_up INTEGER NOT NULL DEFAULT 0,
            endpoint_json TEXT NOT NULL DEFAULT '{}',
            service_checks TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
            updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
            FOREIGN KEY(gid) REFERENCES groups(id) ON DELETE CASCADE
        )
        """,
        "ALTER TABLE profiles ADD COLUMN latency_at INTEGER NOT NULL DEFAULT 0",
        "CREATE INDEX IF NOT EXISTS idx_profiles_name ON profiles(name)",
        "CREATE INDEX IF NOT EXISTS idx_profiles_type ON profiles(type)",
        """
        CREATE TABLE IF NOT EXISTS groups (
            id INTEGER PRIMARY KEY,
            archive INTEGER NOT NULL DEFAULT 0,
            skip_auto_update INTEGER NOT NULL DEFAULT 0,
            name TEXT NOT NULL DEFAULT '',
            url TEXT,
            info TEXT,
            sub_last_update INTEGER NOT NULL DEFAULT 0,
            front_proxy_id INTEGER NOT NULL DEFAULT -1,
            landing_proxy_id INTEGER NOT NULL DEFAULT -1,
            column_width_json TEXT,
            profiles_json TEXT NOT NULL DEFAULT '[]',
            scroll_last_profile INTEGER NOT NULL DEFAULT -1,
            auto_clear_unavailable INTEGER NOT NULL DEFAULT 0,
            test_sort_by INTEGER NOT NULL DEFAULT 0,
            traffic_sort_by INTEGER NOT NULL DEFAULT 0,
            test_items_to_show INTEGER NOT NULL DEFAULT 0,
            type_sort_by INTEGER NOT NULL DEFAULT 0,
            sub_options_json TEXT NOT NULL DEFAULT '{}',
            sub_metadata_json TEXT NOT NULL DEFAULT '{}',
            endpoint_json TEXT NOT NULL DEFAULT '{}',
            sort_method INTEGER NOT NULL DEFAULT 0,
            sort_descending INTEGER NOT NULL DEFAULT 0,
            route_id INTEGER NOT NULL DEFAULT -1,
            import_sub_route INTEGER NOT NULL DEFAULT 0,
            pinned_json TEXT NOT NULL DEFAULT '[]',
            show_services INTEGER NOT NULL DEFAULT 1,
            visible_columns INTEGER NOT NULL DEFAULT 79,
            custom_order_json TEXT NOT NULL DEFAULT '[]',
            created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
            updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS groups_order (
            group_id INTEGER NOT NULL PRIMARY KEY,
            display_order INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS route_profiles (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL DEFAULT '',
            default_outbound_id INTEGER NOT NULL DEFAULT -1,
            is_raw INTEGER NOT NULL DEFAULT 0,
            raw_route TEXT NOT NULL DEFAULT '',
            prevent_modifications INTEGER NOT NULL DEFAULT 0,
            is_remote INTEGER NOT NULL DEFAULT 0,
            remote_url TEXT NOT NULL DEFAULT '',
            auto_update INTEGER NOT NULL DEFAULT 0,
            remote_last_update INTEGER NOT NULL DEFAULT 0,
            endpoint_profile_ids TEXT NOT NULL DEFAULT '[]',
            inner_hop_endpoint_ids TEXT NOT NULL DEFAULT '[]',
            dns_json TEXT NOT NULL DEFAULT '',
            custom_dns INTEGER NOT NULL DEFAULT 0,
            source_gid INTEGER NOT NULL DEFAULT -1,
            remote_rules TEXT,
            display_order INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
            updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS route_rules (
            route_profile_id INTEGER NOT NULL,
            rule_order INTEGER NOT NULL,
            name TEXT NOT NULL DEFAULT '',
            type INTEGER NOT NULL DEFAULT 0,
            ip_version TEXT,
            network TEXT,
            protocol TEXT,
            inbound_json TEXT,
            domain_json TEXT,
            domain_suffix_json TEXT,
            domain_keyword_json TEXT,
            domain_regex_json TEXT,
            source_ip_cidr_json TEXT,
            source_ip_is_private INTEGER NOT NULL DEFAULT 0,
            ip_cidr_json TEXT,
            ip_is_private INTEGER NOT NULL DEFAULT 0,
            source_port_json TEXT,
            source_port_range_json TEXT,
            port_json TEXT,
            port_range_json TEXT,
            process_name_json TEXT,
            process_path_json TEXT,
            process_path_regex_json TEXT,
            rule_set_json TEXT,
            invert INTEGER NOT NULL DEFAULT 0,
            outbound_id INTEGER NOT NULL DEFAULT -2,
            action TEXT NOT NULL DEFAULT 'route',
            reject_method TEXT,
            no_drop INTEGER NOT NULL DEFAULT 0,
            override_address TEXT,
            override_port TEXT,
            sniffers_json TEXT,
            sniff_override_dest INTEGER NOT NULL DEFAULT 0,
            strategy TEXT,
            wifi_ssid_json TEXT,
            wifi_bssid_json TEXT,
            tls_spoof TEXT,
            tls_spoof_method TEXT,
            package_name_json TEXT,
            tls_fragment INTEGER NOT NULL DEFAULT 0,
            tls_fragment_fallback_delay TEXT,
            tls_record_fragment INTEGER NOT NULL DEFAULT 0,
            disabled INTEGER NOT NULL DEFAULT 0,
            network_type_json TEXT,
            network_is_expensive INTEGER NOT NULL DEFAULT 0,
            package_name_regex_json TEXT,
            PRIMARY KEY (route_profile_id, rule_order),
            FOREIGN KEY(route_profile_id) REFERENCES route_profiles(id) ON DELETE CASCADE
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS otp_profiles (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL DEFAULT '',
            issuer TEXT NOT NULL DEFAULT '',
            secret TEXT NOT NULL DEFAULT '',
            algorithm INTEGER NOT NULL DEFAULT 0,
            type INTEGER NOT NULL DEFAULT 0,
            digits INTEGER NOT NULL DEFAULT 6,
            period INTEGER NOT NULL DEFAULT 30,
            counter INTEGER NOT NULL DEFAULT 0,
            sort_order INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
            updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS markers (
            key TEXT PRIMARY KEY,
            marked_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS ip_lists (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL DEFAULT '',
            related_test_id INTEGER NOT NULL DEFAULT -1,
            role INTEGER NOT NULL DEFAULT 0,
            source_kind INTEGER NOT NULL DEFAULT 0,
            source TEXT NOT NULL DEFAULT '',
            auto_update INTEGER NOT NULL DEFAULT 0,
            update_interval INTEGER NOT NULL DEFAULT 1440,
            last_update INTEGER NOT NULL DEFAULT 0,
            last_error TEXT NOT NULL DEFAULT '',
            sort_order INTEGER NOT NULL DEFAULT 0,
            entries_generation INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
            updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS ip_list_entries (
            ip_list_id INTEGER NOT NULL,
            generation INTEGER NOT NULL DEFAULT 0,
            entry_order INTEGER NOT NULL,
            cidr TEXT NOT NULL,
            port INTEGER NOT NULL DEFAULT 0,
            latency_ms INTEGER NOT NULL DEFAULT 0,
            loss_pct INTEGER,
            PRIMARY KEY (ip_list_id, generation, entry_order),
            FOREIGN KEY (ip_list_id) REFERENCES ip_lists(id) ON DELETE CASCADE
        ) WITHOUT ROWID
        """,
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_ip_list_entries_target ON ip_list_entries(ip_list_id, generation, cidr, port)",
        """
        CREATE TABLE IF NOT EXISTS ip_scans (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL DEFAULT '',
            kind INTEGER NOT NULL DEFAULT 0,
            sort_order INTEGER NOT NULL DEFAULT 0,
            base_list_id INTEGER NOT NULL DEFAULT -1,
            result_list_id INTEGER NOT NULL DEFAULT -1,
            config_json TEXT NOT NULL DEFAULT '{}',
            status INTEGER NOT NULL DEFAULT 0,
            mode INTEGER NOT NULL DEFAULT 0,
            snapshot_list_id INTEGER NOT NULL DEFAULT -1,
            seed INTEGER NOT NULL DEFAULT 0,
            cursor INTEGER NOT NULL DEFAULT 0,
            total INTEGER NOT NULL DEFAULT 0,
            spec_hash TEXT NOT NULL DEFAULT '',
            rescan_snapshot_list_id INTEGER NOT NULL DEFAULT -1,
            rescan_cursor INTEGER NOT NULL DEFAULT 0,
            rescan_total INTEGER NOT NULL DEFAULT 0,
            found INTEGER NOT NULL DEFAULT 0,
            removed INTEGER NOT NULL DEFAULT 0,
            last_error TEXT NOT NULL DEFAULT '',
            started_at INTEGER NOT NULL DEFAULT 0,
            finished_at INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
            updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
        )
        """,
    ).map { it.trimIndent() }
}
