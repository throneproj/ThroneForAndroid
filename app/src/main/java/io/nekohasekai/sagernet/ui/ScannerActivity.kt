package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ShortcutManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import com.google.zxing.Result
import com.king.zxing.CameraScan
import com.king.zxing.DecodeConfig
import com.king.zxing.DecodeFormatManager
import com.king.zxing.DefaultCameraScan
import com.king.zxing.analyze.QRCodeAnalyzer
import com.king.zxing.util.CodeUtils
import com.king.zxing.util.LogUtils
import com.king.zxing.util.PermissionUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutScannerBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.applyInsetMargin
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max


class ScannerActivity : ThemedActivity(),
    CameraScan.OnScanResultCallback {

    lateinit var binding: LayoutScannerBinding
    lateinit var cameraScan: CameraScan

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 25) getSystemService<ShortcutManager>()!!.reportShortcutUsed("scan")
        binding = LayoutScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.ivFlashlight.applyInsetMargin(bottom = true, horizontal = true)
        binding.ivPhotoLibrary.applyInsetMargin(bottom = true, horizontal = true)
        binding.ivPhotoLibrary.setOnClickListener {
            startFilesForResult(importCodeFile, "image/*")
        }

        // Without a camera (TVs) the images are the only source: straight to the picker.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            binding.ivFlashlight.isVisible = false
            if (savedInstanceState == null) {
                Toast.makeText(this, R.string.scanner_no_camera, Toast.LENGTH_LONG).show()
                startFilesForResult(importCodeFile, "image/*")
            }
            return
        }

        initCameraScan()
        startCamera()
        binding.ivFlashlight.setOnClickListener { toggleTorchState() }
    }

    /** Images from the gallery: every QR code found goes to the main window in one batch (importFromFiles). */
    val importCodeFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        runOnDefaultDispatcher {
            val texts = ArrayList<String>()
            try {
                uris.forEachTry { uri ->
                    val text = try {
                        decodeImage(uri)
                    } catch (e: OutOfMemoryError) {
                        throw IOException(e.readableMessage, e)
                    }
                    text?.takeIf { it.isNotBlank() }?.let(texts::add)
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    Toast.makeText(app, e.readableMessage, Toast.LENGTH_LONG).show()
                }
            }
            onMainDispatcher {
                if (texts.isNotEmpty()) {
                    importTexts(texts)
                } else if (uris.isNotEmpty()) {
                    Toast.makeText(app, R.string.action_import_err, Toast.LENGTH_SHORT).show()
                }
                if (uris.isNotEmpty() || !::cameraScan.isInitialized) finish()
            }
        }
    }

    /**
     * The QR code in the image, looked for with the long side shrunk to the first of [DECODE_SIDES] (zxing copies
     * every pixel twice more); an image that was shrunk and showed nothing gets one more try at the next size.
     */
    private fun decodeImage(uri: Uri): String? {
        for (side in DECODE_SIDES) {
            val (bitmap, scaled) = loadBitmap(uri, side) ?: return null
            val text = try {
                CodeUtils.parseCodeResult(bitmap, DecodeFormatManager.QR_CODE_HINTS)?.text
            } finally {
                bitmap.recycle()
            }
            if (text != null || !scaled) return text
        }
        return null
    }

    /** The image with its long side at most [side] px, and whether it had to be shrunk; null when it cannot be read. */
    private fun loadBitmap(uri: Uri, side: Int): Pair<Bitmap, Boolean>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var scaled = false
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                val sample = sampleSize(info.size.width, info.size.height, side)
                if (sample > 1) {
                    decoder.setTargetSampleSize(sample)
                    scaled = true
                }
            }
            return bitmap to scaled
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (contentResolver.openInputStream(uri) ?: return null).use { BitmapFactory.decodeStream(it, null, options) }
        options.inSampleSize = sampleSize(options.outWidth, options.outHeight, side)
        options.inJustDecodeBounds = false
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        return bitmap?.let { it to (options.inSampleSize > 1) }
    }

    /** The power of two that brings the long side of a [width] x [height] image down to at most [side]. */
    private fun sampleSize(width: Int, height: Int, side: Int): Int {
        var sample = 1
        while (max(width, height) / sample > side) sample *= 2
        return sample
    }

    var finished = AtomicBoolean(false)

    /** @return true when the result is consumed (no further processing), false to continue. */
    override fun onScanResultCallback(result: Result?): Boolean {
        if (finished.getAndSet(true)) return true
        val text = result?.text
        if (text.isNullOrBlank()) {
            Toast.makeText(app, R.string.action_import_err, Toast.LENGTH_SHORT).show()
        } else {
            importTexts(listOf(text))
        }
        finish()
        return true
    }

    /** The main window imports them (SubscribeFlows): the URL choice, deep links, or profiles into the current group. */
    private fun importTexts(texts: List<String>) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putStringArrayListExtra(MainActivity.EXTRA_IMPORT_TEXTS, ArrayList(texts))
        })
    }

    fun initCameraScan() {
        cameraScan = DefaultCameraScan(this, binding.previewView)
        // The whole preview is decoded, not just the frame drawn on it (0.8 of the short side by default).
        cameraScan.setAnalyzer(
            QRCodeAnalyzer(DecodeConfig().setHints(DecodeFormatManager.QR_CODE_HINTS).setFullAreaScan(true))
        )
        cameraScan.setOnScanResultCallback(this)
        cameraScan.setNeedAutoZoom(true)
    }

    fun startCamera() {
        if (PermissionUtils.checkPermission(this, Manifest.permission.CAMERA)) {
            cameraScan.startCamera()
        } else {
            LogUtils.d("checkPermissionResult != PERMISSION_GRANTED")
            PermissionUtils.requestPermission(
                this, Manifest.permission.CAMERA, CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun releaseCamera() {
        if (::cameraScan.isInitialized) cameraScan.release()
    }

    protected fun toggleTorchState() {
        val isTorch = cameraScan.isTorchEnabled
        cameraScan.enableTorch(!isTorch)
        binding.ivFlashlight.isSelected = !isTorch
    }

    val CAMERA_PERMISSION_REQUEST_CODE = 0X86

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            requestCameraPermissionResult(permissions, grantResults)
        }
    }

    fun requestCameraPermissionResult(permissions: Array<String>, grantResults: IntArray) {
        if (PermissionUtils.requestPermissionsResult(
                Manifest.permission.CAMERA, permissions, grantResults
            )
        ) {
            startCamera()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        releaseCamera()
        super.onDestroy()
    }

    private companion object {
        /** Long sides in px: a QR code reads at the first, a hi-res photo stays far from the heap limit at both. */
        val DECODE_SIDES = intArrayOf(2048, 4096)
    }
}
