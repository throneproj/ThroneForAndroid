package io.nekohasekai.sagernet.bg.test

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One test session request. Empty/zero/negative option fields are filled from the settings in :bg
 * (`test_url`, `url_test_timeout_ms`, `test_concurrent`, `speed_test_mode`, `speed_test_timeout_ms`, `simple_dl_url`).
 * With [testCurrent] the running instance is measured and [profileIds] is ignored.
 */
@Parcelize
data class TestSpec(
    val kind: Int,
    val profileIds: LongArray = LongArray(0),
    val testCurrent: Boolean = false,
    val scopeLabel: String = "",
    val url: String = "",
    val timeoutMs: Int = 0,
    val concurrency: Int = 0,
    val speedMode: Int = -1,
    val speedTimeoutMs: Int = 0,
    val simpleDlUrl: String = "",
) : Parcelable {

    override fun equals(other: Any?): Boolean = this === other ||
        other is TestSpec && kind == other.kind && profileIds.contentEquals(other.profileIds) &&
        testCurrent == other.testCurrent && scopeLabel == other.scopeLabel && url == other.url &&
        timeoutMs == other.timeoutMs && concurrency == other.concurrency && speedMode == other.speedMode &&
        speedTimeoutMs == other.speedTimeoutMs && simpleDlUrl == other.simpleDlUrl

    override fun hashCode(): Int = 31 * kind + profileIds.contentHashCode()

    companion object {
        const val KIND_URL = 0
        const val KIND_IP = 1
        const val KIND_SPEED = 2

        /** The service availability checks (runServiceChecks): never automatic, the menus ask first. */
        const val KIND_SERVICE = 3

        /** kMaxServiceCheckProfiles: every profile sends about 20 requests to third-party services. */
        const val SERVICE_MAX_PROFILES = 100

        /** Service checks of more profiles than this are confirmed first (mainwindow_setup.cpp startServiceChecks). */
        const val SERVICE_CONFIRM_ABOVE = 20
    }
}
