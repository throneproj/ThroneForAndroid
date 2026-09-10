package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V2RayFragmentTest {

    @Test
    fun vlessEncodedChineseFragmentDecodedAsName() {
        val bean = parseV2Ray(
            "vless://00000000-0000-0000-0000-000000000001@srv.example.com:443" +
                    "?encryption=none&type=tcp&security=tls#%E4%B8%AD%E6%96%87%E8%8A%82%E7%82%B9"
        )

        assertEquals("中文节点", bean.name)
        assertEquals("srv.example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("00000000-0000-0000-0000-000000000001", bean.uuid)
    }

    @Test
    fun vlessRawChineseFragmentDecodedAsName() {
        val bean = parseV2Ray(
            "vless://00000000-0000-0000-0000-000000000002@srv.example.com:443" +
                    "?encryption=none&type=tcp&security=tls#中文节点"
        )

        assertEquals("中文节点", bean.name)
        assertEquals("srv.example.com", bean.serverAddress)
    }

    @Test
    fun trojanEncodedFragmentDecodedAsName() {
        val bean = parseTrojan(
            "trojan://pass123@srv.example.com:443?security=tls#%E4%B8%AD%E6%96%87"
        )

        assertEquals("中文", bean.name)
        assertEquals("pass123", bean.password)
        assertEquals("srv.example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
    }

    @Test
    fun malformedVlessLinkReportsReadableError() {
        val exception = assertThrows(IllegalStateException::class.java) {
            parseV2Ray("vless://not a valid link")
        }

        assertTrue(exception.message!!.contains("invalid v2ray link"))
    }

    @Test
    fun malformedTrojanLinkReportsReadableError() {
        val exception = assertThrows(IllegalStateException::class.java) {
            parseTrojan("trojan://not a valid link")
        }

        assertTrue(exception.message!!.contains("invalid trojan link"))
    }
}