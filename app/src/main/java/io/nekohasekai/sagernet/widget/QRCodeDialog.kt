package io.nekohasekai.sagernet.widget

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ui.MainActivity
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

class QRCodeDialog() : DialogFragment() {

    companion object {
        private const val KEY_URL = "io.nekohasekai.sagernet.QRCodeDialog.KEY_URL"
        private const val KEY_NAME = "io.nekohasekai.sagernet.QRCodeDialog.KEY_NAME"

        private fun hints(text: String): Map<EncodeHintType, Any> =
            if (StandardCharsets.ISO_8859_1.newEncoder().canEncode(text)) emptyMap()
            else mapOf(EncodeHintType.CHARACTER_SET to StandardCharsets.UTF_8.name())

        /** Whether [text] fits in one code at the error correction the dialog draws with (zxing's default, L). */
        fun fits(text: String): Boolean = try {
            Encoder.encode(text, ErrorCorrectionLevel.L, hints(text))
            true
        } catch (e: WriterException) {
            false
        }
    }

    constructor(url: String, displayName: String) : this() {
        arguments = bundleOf(
            Pair(KEY_URL, url), Pair(KEY_NAME, displayName)
        )
    }

    /** The rounded surface of the alert dialogs; the platform dialog theme would draw its own. */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply { window?.setBackgroundDrawableResource(R.drawable.bg_dialog) }

    /**
     * Based on:
     * https://android.googlesource.com/platform/
    packages/apps/Settings/+/0d706f0/src/com/android/settings/wifi/qrcode/QrCodeGenerator.java
     * https://android.googlesource.com/platform/
    packages/apps/Settings/+/8a9ccfd/src/com/android/settings/wifi/dpp/WifiDppQrCodeGeneratorFragment.java#153
     */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ) = try {
        // get display size
        var pixelMin = 0

        try {
            val displayMetrics: DisplayMetrics = requireContext().resources.displayMetrics
            val height: Int = displayMetrics.heightPixels
            val width: Int = displayMetrics.widthPixels
            pixelMin = if (height > width) width else height
            pixelMin = (pixelMin * 0.8).roundToInt()
        } catch (e: Exception) {
        }

        val size = if (pixelMin > 0) pixelMin else resources.getDimensionPixelSize(R.dimen.qrcode_size)

        // draw QR Code
        val url = arguments?.getString(KEY_URL)!!
        val displayName = arguments?.getString(KEY_NAME)!!

        val qrBits = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints(url))
        LinearLayout(context).apply {
            // Layout
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = dp2px(24)
            setPadding(padding, padding, padding, padding)

            // QR Code Image View
            addView(ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                // scaled down, still square, when the padded dialog is narrower than the code
                adjustViewBounds = true
                val pixels = IntArray(size * size)
                for (y in 0 until size) {
                    val row = y * size
                    for (x in 0 until size) pixels[row + x] = if (qrBits.get(x, y)) Color.BLACK else Color.WHITE
                }
                setImageBitmap(Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
                    setPixels(pixels, 0, size, 0, 0, size, size)
                })
            })

            // Text View
            addView(TextView(context).apply {
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = displayName
            })
        }
    } catch (e: WriterException) {
        Logs.w(e)
        (activity as MainActivity).snackbar(e.readableMessage).show()
        dismiss()
        null
    }
}