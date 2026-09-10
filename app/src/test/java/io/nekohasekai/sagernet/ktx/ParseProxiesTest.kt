package io.nekohasekai.sagernet.ktx

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseProxiesTest {

    private fun vmessLink(name: String, id: String): String {
        val json = "{\"v\":\"2\",\"ps\":\"$name\",\"add\":\"srv.example.com\"," +
                "\"port\":\"443\",\"id\":\"$id\",\"aid\":\"0\",\"net\":\"tcp\"," +
                "\"type\":\"none\",\"host\":\"\",\"path\":\"\",\"tls\":\"\"}"
        return "vmess://" + java.util.Base64.getEncoder().encodeToString(json.toByteArray())
    }

    @Test
    fun multipleLinksOnSingleLineAreSplitByScheme() = runBlocking {
        val text = vmessLink("node1", "00000000-0000-0000-0000-000000000001") +
                " " + vmessLink("node2", "00000000-0000-0000-0000-000000000002")

        val beans = parseProxies(text)

        assertEquals(2, beans.size)
        assertEquals(
            setOf("node1", "node2"),
            beans.map { it.displayName() }.toSet()
        )
    }

    @Test
    fun mixedTextWithSubscriptionLinkDoesNotInterruptParsing() = runBlocking {
        val text = vmessLink("node1", "00000000-0000-0000-0000-000000000001") + "\n" +
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
        val text = vmessLink("node1", "00000000-0000-0000-0000-000000000001") + "\n" +
                vmessLink("node2", "00000000-0000-0000-0000-000000000002")

        val beans = parseProxies(text)

        assertEquals(2, beans.size)
    }

    @Test
    fun blankLinesAreIgnored() = runBlocking {
        val text = "\n" + vmessLink("node1", "00000000-0000-0000-0000-000000000001") + "\n\n"

        val beans = parseProxies(text)

        assertEquals(1, beans.size)
    }

    @Test
    fun malformedLinkInMixedTextIsSkippedNotThrown() = runBlocking {
        val text = vmessLink("node1", "00000000-0000-0000-0000-000000000001") + "\n" +
                "vmess://not-a-valid-base64!!!"

        val beans = parseProxies(text)

        assertEquals(1, beans.size)
        assertEquals("node1", beans[0].displayName())
    }
}