package io.nekohasekai.sagernet.ktx

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseProxiesTest {

    // 使用 vless 标准 URL 格式构造测试链接（vmess JSON 路径依赖 Android TextUtils，JVM 不可测）
    private fun vlessLink(name: String, id: String): String {
        return "vless://$id@srv.example.com:443?encryption=none&type=tcp&security=tls" +
                "&sni=srv.example.com#$name"
    }

    @Test
    fun multipleLinksOnSingleLineAreSplitByScheme() = runBlocking {
        val text = vlessLink("node1", "00000000-0000-0000-0000-000000000001") +
                " " + vlessLink("node2", "00000000-0000-0000-0000-000000000002")

        val beans = parseProxies(text)

        assertEquals(2, beans.size)
        assertEquals(
            setOf("node1", "node2"),
            beans.map { it.displayName() }.toSet()
        )
    }

    @Test
    fun mixedTextWithSubscriptionLinkDoesNotInterruptParsing() = runBlocking {
        val text = vlessLink("node1", "00000000-0000-0000-0000-000000000001") + "\n" +
                "clash://install-config?url=https%3A%2F%2Fexample.com%2Fsub"

        val beans = parseProxies(text)

        assertEquals(1, beans.size)
        assertEquals("node1", beans[0].displayName())
    }

    @Test
    fun singleSubscriptionLinkTriggersSubscriptionJump() = runBlocking {
        val text = "clash://install-config?url=https%3A%2F%2Fexample.com%2Fsub"

        val exception = runCatching { parseProxies(text) }.exceptionOrNull()

        assertTrue(exception is SubscriptionFoundException)
    }

    @Test
    fun lineParseResultIsPreferredWhenCountsEqual() = runBlocking {
        // 每行一条链接：行级解析与链接级解析结果数量一致，应采用行级结果
        val text = vlessLink("node1", "00000000-0000-0000-0000-000000000001") + "\n" +
                vlessLink("node2", "00000000-0000-0000-0000-000000000002")

        val beans = parseProxies(text)

        assertEquals(2, beans.size)
    }

    @Test
    fun blankLinesAreIgnored() = runBlocking {
        val text = "\n" + vlessLink("node1", "00000000-0000-0000-0000-000000000001") + "\n\n"

        val beans = parseProxies(text)

        assertEquals(1, beans.size)
    }

    @Test
    fun malformedLinkInMixedTextIsSkippedNotThrown() = runBlocking {
        // 未闭合的 IPv6 括号使标准 URL 解析确定失败
        val text = vlessLink("node1", "00000000-0000-0000-0000-000000000001") + "\n" +
                "vless://[invalid"

        val beans = parseProxies(text)

        assertEquals(1, beans.size)
        assertEquals("node1", beans[0].displayName())
    }
}