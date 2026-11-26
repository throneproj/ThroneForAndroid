package io.nekohasekai.sagernet.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The live graphs' points after a serial (bg.LiveStatsRecorder): [times] in elapsedRealtime ms, [values] [FIELDS] per
 * point (NaN where a point has no value; a stop is a point without any). A [session] other than the one asked for means
 * the service process is a new one, and the batch then holds its whole history.
 */
@Parcelize
class LiveStatsBatch(
    val session: Long = 0L,
    val serial: Long = 0L,
    val times: LongArray = LongArray(0),
    val values: FloatArray = FloatArray(0),
) : Parcelable {

    companion object {
        /** Bytes per second. */
        const val PROXY_DOWN = 0
        const val PROXY_UP = 1
        const val DIRECT_DOWN = 2
        const val DIRECT_UP = 3

        /** The service process (which runs the core): percent of all cores, resident bytes, open connections. */
        const val CPU = 4
        const val MEMORY = 5
        const val CONNECTIONS = 6
        const val FIELDS = 7
    }
}
