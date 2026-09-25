package io.nekohasekai.sagernet

object Key {

    const val DB_PROFILE = "sager_net.db"

    // Android-only keys of the configuration store; the desktop keys are in database.SettingsRegistry.

    const val APP_THEME = "appTheme"
    const val USE_SYSTEM_THEME = "useSystemTheme"
    const val NIGHT_THEME = "nightTheme"
    const val AMOLED_THEME = "amoledTheme"
    const val APP_LANGUAGE = "appLanguage"
    const val SERVICE_MODE = "serviceMode"
    const val MODE_VPN = "vpn"
    const val MODE_PROXY = "proxy"

    const val PROXY_APPS = "proxyApps"
    const val BYPASS_MODE = "bypassMode"
    const val INDIVIDUAL = "individual"
    const val METERED_NETWORK = "meteredNetwork"

    const val SHOW_DIRECT_SPEED = "showDirectSpeed"
    const val SHOW_GROUP_IN_NOTIFICATION = "showGroupInNotification"
    const val NOTIFICATION_ACTIONS = "notificationActions"

    const val HTTP_PROXY_BYPASS = "httpProxyBypass"

    const val NETWORK_CHANGE_RESET_CONNECTIONS = "networkChangeResetConnections"
    const val WAKE_RESET_CONNECTIONS = "wakeResetConnections"
    const val LOG_BUF_SIZE = "logBufSize"
    const val ALWAYS_SHOW_ADDRESS = "alwaysShowAddress"

    const val HIDE_FROM_RECENT_APPS = "hideFromRecentApps"
    const val PREVIEW_HINT_DISMISSED_VERSION = "previewHintDismissedVersion"
    const val GROUP_LAYOUT_MODE = "groupLayoutMode"
    const val YACD_URL = "yacdURL"

    const val PROFILE_DIRTY = "profileDirty"
    const val PROFILE_ID = "profileId"
    const val PROFILE_GROUP = "profileGroup"
    const val PROFILE_CURRENT = "profileCurrent"

    const val SERVER_CONFIG = "serverConfig"

    const val GROUP_NAME = "groupName"
    const val GROUP_TYPE = "groupType"

    const val SUBSCRIPTION_LINK = "subscriptionLink"

    //

    const val APP_TLS_VERSION = "appTLSVersion"

    const val UPDATE_CHECK_AUTO = "updateCheckAuto"
    const val UPDATE_SKIPPED_VERSION_CODE = "updateSkippedVersionCode"
    const val RESUME_AFTER_UPDATE = "resumeAfterUpdate"
    const val BATTERY_PROMPT_SHOWN = "batteryPromptShown"
    const val LOG_EXPORT_REDACT = "logExportRedact"
    const val HWID_FALLBACK = "hwidFallback"
    const val WIFI_PERMISSION_ASKED = "wifiPermissionAsked"
    const val SERVICE_ERROR = "serviceError"
    const val SERVICE_ERROR_DNS = "serviceErrorDns"

    const val WEBDAV_SERVER = "webdavServer"
    const val WEBDAV_USERNAME = "webdavUsername"
    const val WEBDAV_PASSWORD = "webdavPassword"
    const val WEBDAV_PATH = "webdavPath"
}

object Action {
    const val SERVICE = "io.nekohasekai.sagernet.SERVICE"
    const val CLOSE = "io.nekohasekai.sagernet.CLOSE"
    const val RELOAD = "io.nekohasekai.sagernet.RELOAD"

    // const val SWITCH_WAKE_LOCK = "io.nekohasekai.sagernet.SWITCH_WAKELOCK"
    const val RESET_UPSTREAM_CONNECTIONS = "io.nekohasekai.sagernet.RESET_UPSTREAM_CONNECTIONS"

    // Every action of the service receiver needs its own branch there: its fallback does nothing.
    const val SWITCH_NEXT = "io.nekohasekai.sagernet.SWITCH_NEXT"
    const val SWITCH_PREVIOUS = "io.nekohasekai.sagernet.SWITCH_PREVIOUS"
    const val SWITCH_PROFILE = "io.nekohasekai.sagernet.SWITCH_PROFILE"
    const val REFRESH_WIFI_STATE = "io.nekohasekai.sagernet.REFRESH_WIFI_STATE"

    /** The auto-selector [EXTRA_PROFILE_ID] goes back to automatic in the running core. */
    const val AUTO_SELECTOR_AUTOMATIC = "io.nekohasekai.sagernet.AUTO_SELECTOR_AUTOMATIC"
    const val EXTRA_PROFILE_ID = "profileId"
}
