package moe.matsuri.nb4a.utils

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import libcore.StringBox
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.Deflater
import java.util.zip.Inflater

object Util {

    /**
     * 取两个文本之间的文本值
     *
     * @param text  源文本 比如：欲取全文本为 12345
     * @param left  文本前面
     * @param right 后面文本
     * @return 返回 String
     */
    fun getSubString(text: String, left: String?, right: String?): String {
        var zLen: Int
        if (left.isNullOrEmpty()) {
            zLen = 0
        } else {
            zLen = text.indexOf(left)
            if (zLen > -1) {
                zLen += left.length
            } else {
                zLen = 0
            }
        }
        var yLen = if (right == null) -1 else text.indexOf(right, zLen)
        if (yLen < 0 || right.isNullOrEmpty()) {
            yLen = text.length
        }
        return text.substring(zLen, yLen)
    }

    // Base64 for all

    fun b64EncodeUrlSafe(s: String): String {
        return b64EncodeUrlSafe(s.toByteArray())
    }

    fun b64EncodeUrlSafe(b: ByteArray): String {
        return String(Base64.encode(b, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE))
    }

    // v2rayN Style
    fun b64EncodeOneLine(b: ByteArray): String {
        return String(Base64.encode(b, Base64.NO_WRAP))
    }

    fun b64EncodeDefault(b: ByteArray): String {
        return String(Base64.encode(b, Base64.DEFAULT))
    }

    fun b64Decode(b: String): ByteArray {
        // 预处理：去除首尾与内部空白（含换行），容忍粘贴产生的格式噪声
        val trimmed = b.trim().replace(Regex("\\s+"), "")

        // 双轮候选：先保留 URL-safe 字符原样解码，再替换为标准字符后解码；
        // 每轮各尝试按长度自动补 padding 的变体
        val cleaned = trimmed.replace("-", "+").replace("_", "/")
        val candidates = LinkedHashSet<String>()
        for (candidate in listOf(trimmed, cleaned)) {
            candidates.add(candidate)
            when (candidate.length % 4) {
                2 -> candidates.add(candidate + "==")
                3 -> candidates.add(candidate + "=")
            }
        }

        // 使用 java.util.Base64（经 core library desugaring 支持 minSdk 21）：
        // 标准 / URL-safe / MIME 宽松三种字母表依次尝试
        val decoders = listOf(
            java.util.Base64.getDecoder(),
            java.util.Base64.getUrlDecoder(),
            java.util.Base64.getMimeDecoder(),
        )

        for (candidate in candidates) {
            for (decoder in decoders) {
                try {
                    return decoder.decode(candidate)
                } catch (_: Exception) {
                }
            }
        }

        throw IllegalStateException("Cannot decode base64")
    }

    fun zlibCompress(input: ByteArray, level: Int): ByteArray {
        // Compress the bytes
        // 1 to 4 bytes/char for UTF-8
        val output = ByteArray(input.size * 4)
        val compressor = Deflater(level).apply {
            setInput(input)
            finish()
        }
        val compressedDataLength: Int = compressor.deflate(output)
        compressor.end()
        return output.copyOfRange(0, compressedDataLength)
    }

    fun zlibDecompress(input: ByteArray): ByteArray {
        val inflater = Inflater()
        val outputStream = ByteArrayOutputStream()

        return outputStream.use {
            val buffer = ByteArray(1024)

            inflater.setInput(input)

            var count = -1
            while (count != 0) {
                count = inflater.inflate(buffer)
                outputStream.write(buffer, 0, count)
            }

            inflater.end()
            outputStream.toByteArray()
        }
    }

    fun map2StringMap(m: Map<*, *>): MutableMap<String, Any?> {
        val o = mutableMapOf<String, Any?>()
        m.forEach {
            if (it.key is String) {
                o[it.key as String] = it.value as Any
            }
        }
        return o
    }

    fun mergeMap(dst: MutableMap<String, Any?>, src: Map<String, Any?>): MutableMap<String, Any?> {
        src.forEach { (k, v) ->
            if (v is Map<*, *> && dst[k] is Map<*, *>) {
                val currentMap = (dst[k] as Map<*, *>).toMutableMap()
                dst[k] = mergeMap(map2StringMap(currentMap), map2StringMap(v))
            } else if (v is List<*>) {
                if (k.startsWith("+")) {  // prepend
                    val dstKey = k.removePrefix("+")
                    var currentList = (dst[dstKey] as? List<*>)?.toMutableList() ?: mutableListOf()
                    currentList = (v + currentList).toMutableList()
                    dst[dstKey] = currentList
                } else if (k.endsWith("+")) {  // append
                    val dstKey = k.removeSuffix("+")
                    var currentList = (dst[dstKey] as? List<*>)?.toMutableList() ?: mutableListOf()
                    currentList = (currentList + v).toMutableList()
                    dst[dstKey] = currentList
                } else {
                    dst[k] = v
                }
            } else {
                dst[k] = v
            }
        }
        return dst
    }

    fun mergeJSON(dst: MutableMap<String, Any?>, j: String) {
        if (j.isBlank()) return
        val src = JavaUtil.gson.fromJson(j, dst.javaClass)
        mergeMap(dst, src)
    }

    // Format Time

    @SuppressLint("SimpleDateFormat")
    val sdf1 = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    fun timeStamp2Text(t: Long): String {
        return sdf1.format(Date(t))
    }

    fun tryToSetField(o: Any, name: String, value: Any) {
        try {
            o.javaClass.getField(name).set(o, value)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("WrongConstant")
    fun collapseStatusBar(context: Context) {
        try {
            val statusBarManager = context.getSystemService("statusbar")
            val collapse = statusBarManager.javaClass.getMethod("collapsePanels")
            collapse.invoke(statusBarManager)
        } catch (_: Exception) {
        }
    }

    fun getStringBox(b: StringBox?): String {
        if (b != null && b.value != null) {
            return b.value
        }
        return ""
    }

    fun decodeFilename(headerValue: String): String {
        val regex = Regex("filename\\*=[^']*''(.+)")
        val match = regex.find(headerValue)
        val encoded = match?.groupValues?.get(1) ?: ""
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    }
}
