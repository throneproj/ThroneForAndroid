package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView
import com.danielstone.materialaboutlibrary.MaterialAboutFragment
import com.danielstone.materialaboutlibrary.items.MaterialAboutActionItem
import com.danielstone.materialaboutlibrary.model.MaterialAboutCard
import com.danielstone.materialaboutlibrary.model.MaterialAboutList
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutAboutBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.plugin.Plugins
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutAboutBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar?.setTitle(R.string.menu_about)

        binding.license.maxLines = LICENSE_COLLAPSED_MAX_LINES
        var isLicenseExpanded = false
        binding.licenseToggle.setOnClickListener {
            val scrollY = binding.aboutScroll.scrollY
            isLicenseExpanded = !isLicenseExpanded
            binding.license.maxLines = if (isLicenseExpanded) {
                Int.MAX_VALUE
            } else {
                LICENSE_COLLAPSED_MAX_LINES
            }
            binding.licenseIndicator.text = if (isLicenseExpanded) "▲" else "▼"
            binding.aboutScroll.doOnPreDraw {
                binding.aboutScroll.scrollTo(0, scrollY)
            }
        }

        childFragmentManager.beginTransaction()
            .replace(R.id.about_fragment_holder, AboutContent())
            .commitAllowingStateLoss()

        runOnDefaultDispatcher {
            val license = view.context.assets.open("LICENSE").bufferedReader().readText()
            onMainDispatcher {
                binding.license.text = license
                Linkify.addLinks(binding.license, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
            }
        }
    }

    companion object {
        private const val LICENSE_COLLAPSED_MAX_LINES = 8
    }

    class AboutContent : MaterialAboutFragment() {

        val requestIgnoreBatteryOptimizations = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (resultCode, _) ->
            if (resultCode == Activity.RESULT_OK) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.about_fragment_holder, AboutContent())
                    .commitAllowingStateLoss()
            }
        }

        override fun getMaterialAboutList(activityContext: Context): MaterialAboutList {
            return MaterialAboutList.Builder()
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(true)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_update_24)
                                .text(R.string.app_version)
                                .subText(SagerNet.appVersionNameForDisplay)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://github.com/throneproj/ThroneForAndroid/releases"
                                    )
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                // Throne has no stable release yet: grey out the item
                                .text(
                                    SpannableString(getString(R.string.check_update_release)).apply {
                                        setSpan(
                                            ForegroundColorSpan(
                                                ContextCompat.getColor(
                                                    activityContext,
                                                    android.R.color.darker_gray
                                                )
                                            ),
                                            0,
                                            length,
                                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                        )
                                    }
                                )
                                .setOnClickAction {
                                    Toast.makeText(
                                        app,
                                        R.string.release_not_available,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .text(R.string.check_update_preview)
                                .setOnClickAction {
                                    checkUpdate()
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_layers_24)
                                .text(getString(R.string.version_x, "sing-box"))
                                .subText(Libcore.versionBox())
                                .setOnClickAction { }
                                .build())
                        .apply {
                            PackageCache.awaitLoadSync()
                            for ((_, pkg) in PackageCache.installedPluginPackages) {
                                try {
                                    val pluginId =
                                        pkg.providers?.get(0)?.loadString(Plugins.METADATA_KEY_ID)
                                    if (pluginId.isNullOrBlank()) continue
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_baseline_nfc_24)
                                            .text(
                                                getString(
                                                    R.string.version_x,
                                                    pluginId
                                                ) + " (${Plugins.displayExeProvider(pkg.packageName)})"
                                            )
                                            .subText("v" + pkg.versionName)
                                            .setOnClickAction {
                                                startActivity(Intent().apply {
                                                    action =
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                                    data = Uri.fromParts(
                                                        "package", pkg.packageName, null
                                                    )
                                                })
                                            }
                                            .build())
                                } catch (e: Exception) {
                                    Logs.w(e)
                                }
                            }
                        }
                        .apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                                if (!pm.isIgnoringBatteryOptimizations(app.packageName)) {
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_baseline_running_with_errors_24)
                                            .text(R.string.ignore_battery_optimizations)
                                            .subText(R.string.ignore_battery_optimizations_sum)
                                            .setOnClickAction {
                                                requestIgnoreBatteryOptimizations.launch(
                                                    Intent(
                                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                        "package:${app.packageName}".toUri()
                                                    )
                                                )
                                            }
                                            .build())
                                }
                            }
                        }
                        .build())
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(true)
                        .title(R.string.project)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_sanitizer_24)
                                .text(R.string.github)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://github.com/throneproj/ThroneForAndroid"

                                    )
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_qu_shadowsocks_foreground)
                                .text(R.string.telegram)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://t.me/MatsuriDayo"
                                    )
                                }
                                .build())
                        .build())
                .build()

        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.layoutParams = (view.layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )).apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            view.findViewById<RecyclerView>(R.id.mal_recyclerview)?.apply {
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = (layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )).apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }

                val cardStrokeWidth = resources.getDimensionPixelSize(
                    R.dimen.card_stroke_width
                )
                val cardStrokeColor = ContextCompat.getColor(
                    requireContext(),
                    R.color.card_stroke
                )
                val cardCornerRadius = resources.getDimension(
                    R.dimen.card_corner_radius
                )
                fun applyApplicationCardStyle(child: View) {
                    (child as? MaterialCardView)?.apply {
                        strokeWidth = cardStrokeWidth
                        strokeColor = cardStrokeColor
                        radius = cardCornerRadius
                        cardElevation = 0f
                    }
                }

                addOnChildAttachStateChangeListener(
                    object : RecyclerView.OnChildAttachStateChangeListener {
                        override fun onChildViewAttachedToWindow(child: View) {
                            applyApplicationCardStyle(child)
                        }

                        override fun onChildViewDetachedFromWindow(child: View) = Unit
                    }
                )
                for (index in 0 until childCount) {
                    applyApplicationCardStyle(getChildAt(index))
                }
            }
        }

        fun checkUpdate() {
            runOnIoDispatcher {
                try {
                    val client = Libcore.newHttpClient().apply {
                        modernTLS()
                        tryProxyOutbound()
                    }
                    val response = client.newRequest().apply {
                        setURL("https://api.github.com/repos/throneproj/ThroneForAndroid/releases/latest")
                    }.execute()
                    val release = JSONObject(Util.getStringBox(response.contentString))
                    val releaseName = release.getString("name")
                    val releaseUrl = release.getString("html_url")
                    // Release name is the git tag, e.g. "v1.4.2-m20-10".
                    // Compare it with the local version name segment by segment.
                    val haveUpdate = releaseName.isNotBlank() &&
                            compareVersionNames(releaseName, BuildConfig.VERSION_NAME) > 0
                    runOnMainDispatcher {
                        if (haveUpdate) {
                            val context = requireContext()
                            MaterialAlertDialogBuilder(context)
                                .setTitle(R.string.update_dialog_title)
                                .setMessage(
                                    context.getString(
                                        R.string.update_dialog_message,
                                        SagerNet.appVersionNameForDisplay,
                                        releaseName
                                    )
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    val intent = Intent(Intent.ACTION_VIEW, releaseUrl.toUri())
                                    context.startActivity(intent)
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        } else {
                            Toast.makeText(app, R.string.check_update_no, Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    runOnMainDispatcher {
                        Toast.makeText(app, e.readableMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        companion object {

            private val numberRegex = Regex("\\d+")

            /**
             * Compares two version names segment by segment (split by "-").
             * Each segment is compared by its numeric groups in order,
             * and left segments dominate right ones, e.g.
             * "v1.2.3-m21-1" > "v1.2.3-m20-100".
             *
             * Returns a positive value if [a] is newer than [b],
             * a negative value if it is older, and 0 if they are equal.
             */
            fun compareVersionNames(a: String, b: String): Int {
                val segmentsA = a.split("-")
                val segmentsB = b.split("-")
                for (i in 0 until maxOf(segmentsA.size, segmentsB.size)) {
                    val segmentA = segmentsA.getOrNull(i).orEmpty()
                    val segmentB = segmentsB.getOrNull(i).orEmpty()
                    val numbersA = extractNumbers(segmentA)
                    val numbersB = extractNumbers(segmentB)
                    if (numbersA.isEmpty() && numbersB.isEmpty()) {
                        val compared = segmentA.compareTo(segmentB)
                        if (compared != 0) return compared
                        continue
                    }
                    for (j in 0 until maxOf(numbersA.size, numbersB.size)) {
                        val numberA = numbersA.getOrNull(j)
                        val numberB = numbersB.getOrNull(j)
                        if (numberA == null) return -1
                        if (numberB == null) return 1
                        if (numberA != numberB) return if (numberA < numberB) -1 else 1
                    }
                }
                return 0
            }

            private fun extractNumbers(segment: String): List<Long> =
                numberRegex.findAll(segment).mapNotNull { it.value.toLongOrNull() }.toList()

        }

    }

}
