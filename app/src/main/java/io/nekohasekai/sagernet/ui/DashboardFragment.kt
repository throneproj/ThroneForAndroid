package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutDashboardBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ui.settings.CoreSettingsFragment
import io.nekohasekai.sagernet.widget.applyInsetMargin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The sing-box dashboard (SagerNet/sing-box-dashboard) served by the running core's api service, pre-connected like
 * the desktop's OpenDashboard (mainwindow_system.cpp:462-530): the page stays hidden until its localStorage holds the
 * `throne` server entry that res/dashboard-bootstrap.html seeds there, so the dashboard's setup screen never shows.
 * The core downloads the dashboard on first use and answers 404 until it has it.
 */
class DashboardFragment : ToolbarFragment(R.layout.layout_dashboard), Toolbar.OnMenuItemClickListener {

    private var binding: LayoutDashboardBinding? = null
    private var webView: WebView? = null

    /** `http://127.0.0.1:<port>` of the dashboard loaded or loading, "" when there is none. */
    private var origin = ""
    private var port = 0

    /** The page is connected to this core and on screen. */
    private var shown = false

    /** How the current main-frame load failed: its HTTP status, -1 for a network error, 0 not at all. */
    private var mainFrameStatus = 0
    private var clearHistory = false

    /** Counts documents the WebView started; a check result for an older one is stale. */
    private var pageLoad = 0
    private var checkedLoad = -1

    /** Loads this screen starts make `throne` the active server; the page's own reloads keep the user's choice. */
    private var activate = true
    private var seedReloads = 0
    private var downloadWaitStart = 0L
    private var retryJob: Job? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            webView?.goBack()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.setTitle(R.string.menu_dashboard)
        toolbar?.inflateMenu(R.menu.dashboard_menu)
        toolbar?.setOnMenuItemClickListener(this)
        val binding = LayoutDashboardBinding.bind(view)
        this.binding = binding
        binding.dashboardContent.applyInsetMargin(bottom = true, horizontal = true, ime = true)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        render(reload = true)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        retryJob?.cancel()
        destroyWebView()
        origin = ""
        shown = false
        binding = null
        super.onDestroyView()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId != R.id.action_dashboard_reload) return false
        render(reload = true)
        return true
    }

    /** MainActivity.changeState: the core serves the dashboard only while the service is connected. */
    fun onServiceStateChanged() = render(reload = false)

    private fun render(reload: Boolean) {
        if (binding == null) return
        val state = DataStore.serviceState
        when {
            !DataStore.apiDashboardEnabled -> {
                unload()
                showMessage(
                    R.drawable.ic_baseline_transform_24, R.string.dashboard_off_title,
                    getString(R.string.dashboard_off), R.string.dashboard_core_settings,
                ) {
                    (activity as? MainActivity)?.openSettingsScreen(
                        CoreSettingsFragment::class.java.name, getString(R.string.settings_core)
                    )
                }
            }

            state.connected -> if (reload || origin.isEmpty()) load()

            state.started -> {
                unload()
                showProgress(R.string.connecting)
            }

            state == BaseService.State.Stopping -> {
                unload()
                showProgress(R.string.stopping)
            }

            else -> {
                unload()
                showMessage(
                    R.drawable.ic_baseline_transform_24, R.string.dashboard_stopped_title,
                    getString(R.string.dashboard_stopped), R.string.menu_configuration,
                ) {
                    (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_configuration)
                }
            }
        }
    }

    private fun load() {
        val binding = binding ?: return
        retryJob?.cancel()
        val webView = this.webView ?: createWebView(binding.dashboardWeb)?.also { this.webView = it }
        if (webView == null) {
            origin = ""
            showError(getString(R.string.dashboard_no_webview))
            return
        }
        port = DataStore.coreBoxApiPort
        origin = "http://127.0.0.1:$port"
        activate = true
        seedReloads = 0
        downloadWaitStart = 0L
        mainFrameStatus = 0
        clearHistory = true
        showProgress(R.string.dashboard_loading)
        webView.loadUrl("$origin/dashboard/")
    }

    /** Stops the page, whose streams would otherwise keep knocking on a port nothing serves. */
    private fun unload() {
        retryJob?.cancel()
        if (origin.isEmpty()) return
        origin = ""
        webView?.run {
            stopLoading()
            loadUrl("about:blank")
        }
    }

    private fun isOurs(url: String?): Boolean =
        origin.isNotEmpty() && url != null && (url == origin || url.startsWith("$origin/"))

    /**
     * Writes the `throne` entry when it is missing or stale and reloads, since the page reads its server list only
     * when it starts; the page is shown once the entry is right (or cannot be written at all).
     */
    private fun seed(view: WebView) {
        // onPageFinished also reports the page's own hash navigations: one check per document.
        val document = pageLoad
        if (checkedLoad == document) return
        checkedLoad = document
        view.evaluateJavascript(seedScript("127.0.0.1:$port", DataStore.coreBoxApiSecret, activate)) { result ->
            if (view !== webView || document != pageLoad || origin.isEmpty()) return@evaluateJavascript
            if (result == "\"seeded\"" && seedReloads++ < 2) {
                view.reload()
                return@evaluateJavascript
            }
            if (result != "\"ok\"") Logs.w("dashboard: server entry check returned $result")
            activate = false
            seedReloads = 0
            downloadWaitStart = 0L
            showDashboard()
        }
    }

    /** The core fetches the dashboard when the service starts; a failed fetch is retried at the next start. */
    private fun waitForDownload(view: WebView) {
        val now = SystemClock.elapsedRealtime()
        if (downloadWaitStart == 0L) downloadWaitStart = now
        if (now - downloadWaitStart >= DOWNLOAD_WAIT_MS) {
            showMessage(
                R.drawable.ic_baseline_warning_24, R.string.dashboard_not_downloaded_title,
                getString(R.string.dashboard_not_downloaded), R.string.dashboard_retry,
            ) { render(reload = true) }
            return
        }
        showProgress(R.string.dashboard_downloading)
        retryJob?.cancel()
        retryJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(DOWNLOAD_POLL_MS)
            if (view === webView) view.reload()
        }
    }

    private fun showProgress(@StringRes text: Int) {
        val binding = binding ?: return
        shown = false
        binding.dashboardWeb.isInvisible = true
        binding.dashboardMessage.isVisible = false
        binding.dashboardProgress.isVisible = true
        binding.dashboardProgressText.setText(text)
        syncBack()
    }

    private fun showMessage(
        @DrawableRes icon: Int, @StringRes title: Int, text: CharSequence, @StringRes action: Int, onAction: () -> Unit,
    ) {
        val binding = binding ?: return
        retryJob?.cancel()
        shown = false
        binding.dashboardWeb.isInvisible = true
        binding.dashboardProgress.isVisible = false
        binding.dashboardMessage.isVisible = true
        binding.dashboardMessageIcon.setImageResource(icon)
        binding.dashboardMessageTitle.setText(title)
        binding.dashboardMessageText.text = text
        binding.dashboardMessageAction.setText(action)
        binding.dashboardMessageAction.setOnClickListener { onAction() }
        syncBack()
    }

    private fun showError(text: String) = showMessage(
        R.drawable.ic_baseline_warning_24, R.string.dashboard_error_title, text, R.string.dashboard_retry,
    ) { render(reload = true) }

    private fun showDashboard() {
        val binding = binding ?: return
        shown = true
        binding.dashboardProgress.isVisible = false
        binding.dashboardMessage.isVisible = false
        binding.dashboardWeb.isInvisible = false
        syncBack()
    }

    private fun syncBack() {
        backCallback.isEnabled = shown && webView?.canGoBack() == true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(holder: ViewGroup): WebView? {
        val webView = try {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            WebView(requireContext())
        } catch (e: Exception) {
            // No usable WebView provider: missing, disabled or being updated.
            Logs.w(e)
            return null
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
        }
        webView.webViewClient = Client()
        // Without a chrome client the WebView drops the page's JavaScript dialogs.
        webView.webChromeClient = WebChromeClient()
        holder.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (!isResumed) webView.onPause()
        return webView
    }

    private fun destroyWebView() {
        val webView = webView ?: return
        this.webView = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            if (isOurs(url) || url.startsWith("blob:$origin/")) return false
            // Other sites go to the browser: this WebView holds the API secret.
            val scheme = request.url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") context?.launchCustomTab(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageLoad++
        }

        // Arrives with the response, before onPageStarted, so only onPageFinished consumes it.
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) mainFrameStatus = errorResponse.statusCode
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || view !== webView || !isOurs(request.url.toString())) return
            val detail = error.description?.toString().orEmpty()
            if (detail == "net::ERR_ABORTED") return
            mainFrameStatus = -1
            Logs.w("dashboard: ${request.url}: $detail")
            showError(getString(R.string.dashboard_unreachable, port, detail))
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val status = mainFrameStatus
            mainFrameStatus = 0
            if (view !== webView || !isOurs(url)) return
            if (clearHistory) {
                // What earlier loads left behind: about:blank, pages of the previous service run.
                clearHistory = false
                view.clearHistory()
            }
            when (status) {
                0 -> seed(view)
                -1 -> Unit
                404 -> waitForDownload(view)
                else -> showError(getString(R.string.dashboard_http_error, status))
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = syncBack()

        // Unhandled, a crashed or killed renderer takes the whole app down with it.
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Logs.w("dashboard: the WebView renderer is gone")
            if (view === webView) {
                destroyWebView()
                origin = ""
                render(reload = true)
            }
            return true
        }
    }

    private companion object {
        const val DOWNLOAD_WAIT_MS = 60_000L
        const val DOWNLOAD_POLL_MS = 3_000L

        /**
         * dashboard-bootstrap.html as a check: the `throne` entry of the page's server list (src/api/config.ts) gets
         * this core's address and secret, other servers stay. Answers "ok" when nothing had to change, "seeded" after
         * a write and "error" when the storage is unusable. [activate] also makes it the active server.
         */
        fun seedScript(url: String, secret: String, activate: Boolean): String = """
            (function () {
              var KEY = "sing-box-dashboard.servers", ID = "throne";
              var url = ${JSONObject.quote(url)}, secret = ${JSONObject.quote(secret)};
              try {
                var state = null;
                try { state = JSON.parse(localStorage.getItem(KEY)); } catch (e) {}
                if (!state || typeof state !== "object" || Array.isArray(state)) state = {};
                var servers = Array.isArray(state.servers) ? state.servers : [];
                var entry = null;
                for (var i = 0; i < servers.length; i++) {
                  if (servers[i] && servers[i].id === ID) { entry = servers[i]; break; }
                }
                var changed = false;
                if (!entry) {
                  servers.push({ id: ID, name: "Throne", url: url, secret: secret });
                  changed = true;
                } else if (entry.url !== url || entry.secret !== secret) {
                  entry.url = url;
                  entry.secret = secret;
                  changed = true;
                }
                var activeValid = servers.some(function (s) {
                  return s && s.id === state.activeId && typeof s.url === "string" && s.url !== "";
                });
                if ($activate ? state.activeId !== ID : !activeValid) {
                  state.activeId = ID;
                  changed = true;
                }
                if (!changed) return "ok";
                state.servers = servers;
                localStorage.setItem(KEY, JSON.stringify(state));
                return "seeded";
              } catch (e) {
                return "error";
              }
            })();
        """.trimIndent()
    }
}
