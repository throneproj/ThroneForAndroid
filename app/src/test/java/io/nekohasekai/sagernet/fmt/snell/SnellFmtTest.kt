package io.nekohasekai.sagernet.fmt.snell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SnellFmtTest {

    @Test
    fun standardLinkParses() {
        val bean = parseSnell(
            "snell://mypsk@1.2.3.4:8000?version=6&mode=default&reuse=true#node1"
        )

        assertEquals("1.2.3.4", bean.serverAddress)
        assertEquals(8000, bean.serverPort)
        assertEquals("mypsk", bean.psk)
        assertEquals("node1", bean.name)
        assertEquals(6, bean.version)
        assertEquals("default", bean.mode)
        assertEquals(true, bean.reuse)
    }

    @Test
    fun nonStandardLinkWithSlashInPskFallsBackToRegex() {
        // psk 含 '/' 时标准 URI 解析会把 '/' 当路径，提取不到 psk，应回退 regex 解析
        val bean = parseSnell(
            "snell://abc/def@1.2.3.4:8000?version=4&obfsMode=http&obfsHost=cdn.example.com#%E8%8A%82%E7%82%B9"
        )

        assertEquals("1.2.3.4", bean.serverAddress)
        assertEquals(8000, bean.serverPort)
        assertEquals("abc/def", bean.psk)
        assertEquals("节点", bean.name)
        assertEquals(4, bean.version)
        assertEquals("http", bean.obfsMode)
        assertEquals("cdn.example.com", bean.obfsHost)
    }

    @Test
    fun fallbackParsesQueryAliases() {
        // psk 含 '/' 使标准解析提取不到 psk，触发回退；query 键大小写不敏感
        val bean = parseSnell(
            "snell://psk/123@srv.example.com:9000?Version=5&OBFSMODE=tls&obfs-host=cdn.example.com&REUSE=true#name-x"
        )

        assertEquals("srv.example.com", bean.serverAddress)
        assertEquals(9000, bean.serverPort)
        assertEquals("psk/123", bean.psk)
        assertEquals("name-x", bean.name)
        assertEquals(5, bean.version)
        assertEquals("tls", bean.obfsMode)
        assertEquals("cdn.example.com", bean.obfsHost)
        assertEquals(true, bean.reuse)
    }

    @Test
    fun fallbackParsesIPv6Host() {
        val bean = parseSnell("snell://psk@[2001:db8::1]:9000#v6node")

        assertEquals(9000, bean.serverPort)
        assertEquals("psk", bean.psk)
        assertEquals("v6node", bean.name)
    }

    @Test
    fun invalidLinkThrowsReadableError() {
        val exception = assertThrows(IllegalStateException::class.java) {
            parseSnell("snell://garbage-no-host")
        }

        assertEquals("Invalid snell URL", exception.message)
    }
}