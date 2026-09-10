package moe.matsuri.nb4a.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class B64DecodeTest {

    @Test
    fun decodesStandardBase64WithPadding() {
        val decoded = String(Util.b64Decode("eyJhIjoxfQ=="))

        assertEquals("{\"a\":1}", decoded)
    }

    @Test
    fun decodesBase64WithMissingPadding() {
        // 长度模 4 余 2，缺两个 padding 字符
        val decoded = String(Util.b64Decode("eyJhIjoxfQ"))

        assertEquals("{\"a\":1}", decoded)
    }

    @Test
    fun decodesBase64WithMissingSinglePadding() {
        // "YWJj" 去掉末尾一个字符后长度模 4 余 3，缺一个 padding 字符
        val decoded = String(Util.b64Decode("YWJ"))

        assertEquals("ab", decoded)
    }

    @Test
    fun decodesBase64WithInternalWhitespaceAndNewlines() {
        val decoded = String(Util.b64Decode("eyJhIjox\nfQ==  "))

        assertEquals("{\"a\":1}", decoded)
    }

    @Test
    fun decodesUrlSafeAlphabet() {
        // 标准字母表 "+/8=" 与 URL-safe "-_8"（缺 padding）解码结果一致
        // 位序列：62(111110) 63(111111) 60(111100) → 0xFB 0xFF
        val standard = Util.b64Decode("+/8=")
        val urlSafe = Util.b64Decode("-_8")

        assertArrayEquals(standard, urlSafe)
        assertEquals(0xFB, standard[0].toInt() and 0xFF)
        assertEquals(0xFF, standard[1].toInt() and 0xFF)
    }

    @Test
    fun decodesMultilineWrappedBase64() {
        // 模拟多行换行包裹的 base64（DEFAULT 风格）
        val decoded = String(Util.b64Decode("eyJhIjoxf\nQ=="))

        assertEquals("{\"a\":1}", decoded)
    }

    @Test
    fun throwsReadableErrorOnInvalidInput() {
        // 5 个合法字符：标准/URL 解码器因长度模 4 余 1 失败，MIME 解码器因悬挂字符失败
        val exception = assertThrows(IllegalStateException::class.java) {
            Util.b64Decode("abcde")
        }

        assertEquals("Cannot decode base64", exception.message)
    }

    @Test
    fun roundTripsWithUrlSafeEncoder() {
        val original = "sample-payload-with-url-unsafe-chars-+/="
        // b64EncodeUrlSafe 依赖 Android Base64（JVM 桩不可用），此处用 java 编码器构造输入
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(original.toByteArray())
        val decoded = String(Util.b64Decode(encoded))

        assertEquals(original, decoded)
    }
}