package io.nekohasekai.sagernet.ktx

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.MessageStore
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay

inline fun <T> Iterable<T>.forEachTry(action: (T) -> Unit) {
    var result: Exception? = null
    for (element in this) try {
        action(element)
    } catch (e: Exception) {
        if (result == null) result = e else result.addSuppressed(e)
    }
    if (result != null) {
        throw result
    }
}

val Throwable.readableMessage
    get() = localizedMessage.takeIf { !it.isNullOrBlank() } ?: javaClass.simpleName

fun broadcastReceiver(callback: (Context, Intent) -> Unit): BroadcastReceiver =
    object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = callback(context, intent)
    }

fun Context.listenForPackageChanges(onetime: Boolean = true, callback: () -> Unit) =
    object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            callback()
            if (onetime) context.unregisterReceiver(this)
        }
    }.apply {
        registerReceiver(this, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        })
    }

fun Preference.remove() = parent!!.removePreference(this)

@JvmOverloads
fun DialogFragment.showAllowingStateLoss(fragmentManager: FragmentManager, tag: String? = null) {
    if (!fragmentManager.isStateSaved) show(fragmentManager, tag)
}

val app get() = SagerNet.application

val shortAnimTime by lazy {
    app.resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
}

fun View.crossFadeFrom(other: View) {
    clearAnimation()
    other.clearAnimation()
    if (isVisible && other.isGone) return
    alpha = 0F
    visibility = View.VISIBLE
    animate().alpha(1F).duration = shortAnimTime
    other.animate().alpha(0F).setListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            other.visibility = View.GONE
        }
    }).duration = shortAnimTime
}


/**
 * The message host in three steps: the fragment's activity, the foreground activity recorded by
 * MessageStore, then that window's decor view; a detached or destroyed host never crashes requireActivity().
 */
fun Fragment.snackbar(textId: Int) = snackbar(getString(textId))

fun Fragment.snackbar(text: CharSequence): Snackbar {
    // 1. the fragment's activity
    (activity as? MainActivity)?.takeIf { it.window != null }?.let { host ->
        try {
            return host.snackbar(text)
        } catch (e: Exception) {
            Logs.w(e)
        }
    }
    // 2. the foreground activity recorded by MessageStore
    (MessageStore.getCurrentActivity() as? MainActivity)?.takeIf { it.window != null }
        ?.let { host ->
            try {
                return host.snackbar(text)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    // 3. any activity's decor view (independent of MainActivity's layout)
    (activity ?: MessageStore.getCurrentActivity())?.window?.decorView
        ?.findViewById<View>(android.R.id.content)?.let { decorView ->
            try {
                return Snackbar.make(decorView, text, Snackbar.LENGTH_LONG)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    // Last resort: a detached container shows nothing but never crashes;
    // background flows that must be seen use safeSnackbar (Toast fallback).
    return Snackbar.make(FrameLayout(app), text, Snackbar.LENGTH_LONG)
}

/** [snackbar] with a Toast fallback when no host is usable; never throws. */
fun Fragment.safeSnackbar(text: CharSequence) {
    val host = activity as? MainActivity ?: MessageStore.getCurrentActivity() as? MainActivity
    if (host != null && host.window != null) {
        try {
            host.snackbar(text).show()
            return
        } catch (e: Exception) {
            Logs.w(e)
        }
    }
    Toast.makeText(app, text, Toast.LENGTH_LONG).show()
}

fun ThemedActivity.startFilesForResult(
    launcher: ActivityResultLauncher<String>, input: String
) {
    try {
        return launcher.launch(input)
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
    snackbar(getString(R.string.file_manager_missing)).show()
}

fun Fragment.startFilesForResult(
    launcher: ActivityResultLauncher<String>, input: String
) {
    try {
        return launcher.launch(input)
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
    (requireActivity() as ThemedActivity).snackbar(getString(R.string.file_manager_missing)).show()
}

fun Fragment.needReload() {
    if (DataStore.serviceState.started) {
        snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
            SagerNet.reloadService()
        }.show()
    }
}

fun ThemedActivity.needReload() {
    if (DataStore.serviceState.started) {
        snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
            SagerNet.reloadService()
        }.show()
    }
}

fun Fragment.needRestart() {
    snackbar(R.string.need_restart).setAction(R.string.apply) {
        triggerFullRestart(requireContext())
    }.show()
}

fun triggerFullRestart(ctx: Context) {
    runOnDefaultDispatcher {
        SagerNet.stopService()
        delay(500)
        SagerConnection.restartingApp = true
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_RESTART_BG)
        connection.connect(ctx, RestartCallback {
            ProcessPhoenix.triggerRebirth(ctx, Intent(ctx, MainActivity::class.java))
        })
    }
}

private class RestartCallback(val callback: () -> Unit) : SagerConnection.Callback {
    override fun stateChanged(
        state: BaseService.State,
        profileName: String?,
        msg: String?
    ) {
    }

    override fun onServiceConnected(service: ISagerNetService) {
        callback()
    }
}

fun Context.getColour(@ColorRes colorRes: Int): Int {
    return ContextCompat.getColor(this, colorRes)
}

fun Context.getColorAttr(@AttrRes resId: Int): Int {
    // Transparent when the attribute is missing or unresolvable, instead of crashing
    return try {
        val typedValue = TypedValue()
        if (theme.resolveAttribute(resId, typedValue, true)) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else 0
    } catch (e: Exception) {
        Logs.w(e)
        0
    }
}

const val isOss = BuildConfig.FLAVOR == "oss"
const val isPreview = BuildConfig.PREVIEW

// Resumes once; later results are dropped (CancellableContinuation.tryResume is internal).
fun <T> CancellableContinuation<T>.completeWith(result: Result<T>) {
    if (!isActive) return
    try {
        resumeWith(result)
    } catch (ignored: IllegalStateException) {
    }
}
