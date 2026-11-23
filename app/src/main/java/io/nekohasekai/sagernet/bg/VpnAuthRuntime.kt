package io.nekohasekai.sagernet.bg

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.VpnAuthActivity
import io.throneproj.mobile.Instance
import io.throneproj.mobile.VPNStatusListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * The sign-in prompts of the running instance's OpenVPN/OpenConnect endpoints (the desktop's VpnAuthController without
 * the OTP auto-answer). The core pushes every status change of those endpoints; a new challenge or a refused sign-in
 * posts a notification that opens [VpnAuthActivity], and the UI follows [VpnAuthState] through cbVpnAuthUpdate.
 */
object VpnAuthRuntime {

    const val CHANNEL = "vpn-sign-in"

    private const val NOTIFICATION_TAG = "vpn-auth:"
    private const val NOTIFICATION_ID = 1
    private const val POSTED_FAILURE = "\u001ffailed"

    private class Session(val proxy: ProxyInstance, val service: BaseService.Interface) : VPNStatusListener {
        val box: Instance = proxy.box

        @Volatile
        var closed = false
        var raw: String? = null
        var state = VpnAuthState.EMPTY

        /** Endpoint tag -> what its notification shows: a challenge key or [POSTED_FAILURE]. */
        val posted = HashMap<String, String>()
        var expiry: Job? = null

        override fun onVPNStatus(statusJSON: String?) {
            synchronized(VpnAuthRuntime) {
                if (closed) return
                raw = statusJSON
                refresh(this)
            }
        }

        fun nameOf(tag: String): String = proxy.config.outboundNames[tag] ?: proxy.displayProfileName
    }

    private var session: Session? = null

    /** After the box started: watches its endpoints, when it has any. */
    fun onStarted(proxy: ProxyInstance) {
        val service = proxy.service ?: return
        if (proxy.boxOrNull == null) return
        val started = Session(proxy, service)
        synchronized(this) {
            session?.let(::closeLocked)
            session = started
        }
        val watching = try {
            started.box.watchVPNStatus(started)
        } catch (e: Throwable) {
            Logs.w("vpn sign-in: ${e.readableMessage}")
            false
        }
        if (!watching) synchronized(this) {
            if (session === started) session = null
        }
    }

    fun onClosed(proxy: ProxyInstance) {
        val closed = synchronized(this) {
            val current = session?.takeIf { it.proxy === proxy } ?: return
            session = null
            closeLocked(current)
            current
        }
        if (closed.state != VpnAuthState.EMPTY) closed.service.data.binder.vpnAuthChanged(VpnAuthState.EMPTY.toJson())
    }

    fun stateJson(): String = synchronized(this) { session?.state ?: VpnAuthState.EMPTY }.toJson()

    /** "" or why the answer was not taken. */
    fun submit(tag: String, id: String, answerJson: String): String = withBox { it.submitVPNChallenge(tag, id, answerJson) }

    fun cancel(tag: String, id: String): String = withBox { it.cancelVPNChallenge(tag, id) }

    private fun withBox(action: (Instance) -> Unit): String {
        val box = synchronized(this) { session?.box } ?: return app.getString(R.string.vpn_auth_gone)
        return try {
            action(box)
            ""
        } catch (e: Throwable) {
            e.readableMessage
        }
    }

    private fun closeLocked(session: Session) {
        session.closed = true
        session.expiry?.cancel()
        val context = session.service as Context
        for (tag in session.posted.keys) cancelNotification(context, tag)
        session.posted.clear()
    }

    private fun refresh(session: Session) {
        val now = System.currentTimeMillis()
        val core = VpnAuthState.fromCore(session.raw, session::nameOf)
        val next = core.copy(challenges = core.challenges.filterNot { it.expired(now) })
        scheduleExpiry(session, next, now)
        if (next == session.state) return
        session.state = next
        post(session, next)
        session.service.data.binder.vpnAuthChanged(next.toJson())
    }

    // The core drops an expired challenge itself, but not necessarily the moment it expires.
    private fun scheduleExpiry(session: Session, state: VpnAuthState, now: Long) {
        session.expiry?.cancel()
        val first = state.challenges.filter { it.deadline > 0 }.minOfOrNull { it.deadline * 1000 } ?: return
        session.expiry = runOnDefaultDispatcher {
            delay(first - now)
            synchronized(VpnAuthRuntime) {
                if (!session.closed) refresh(session)
            }
        }
    }

    private fun post(session: Session, state: VpnAuthState) {
        val context = session.service as Context
        val shown = LinkedHashMap<String, String>()
        for (challenge in state.challenges) shown[challenge.tag] = challenge.key
        for (failure in state.failures) {
            if (failure.authFailed && failure.tag !in shown) shown[failure.tag] = POSTED_FAILURE
        }
        for (tag in session.posted.keys - shown.keys) cancelNotification(context, tag)
        // The prompt opens by itself over a screen of the app, so the notification only stays as a way back to it.
        val quiet = session.service.data.binder.uiForeground()
        for ((tag, key) in shown) {
            if (session.posted[tag] == key) continue
            if (key == POSTED_FAILURE) {
                val failure = state.failure(tag) ?: continue
                notify(
                    context, tag, context.getString(R.string.vpn_auth_refused_title, failure.name),
                    failure.error.ifEmpty { context.getString(R.string.vpn_auth_refused_text) }, quiet,
                )
            } else {
                val challenge = state.challenge(tag) ?: continue
                notify(
                    context, tag, context.getString(R.string.vpn_auth_notification_title, challenge.name),
                    challenge.error.ifEmpty { challenge.message }.ifEmpty { context.getString(R.string.vpn_auth_notification_text) },
                    quiet,
                )
            }
        }
        session.posted.clear()
        session.posted.putAll(shown)
    }

    private fun notify(context: Context, tag: String, title: String, text: String, quiet: Boolean) {
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_sign_in)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(
                    context, tag.hashCode(), VpnAuthActivity.intent(context, tag).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setSilent(quiet)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG + tag, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun cancelNotification(context: Context, tag: String) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG + tag, NOTIFICATION_ID)
    }
}
