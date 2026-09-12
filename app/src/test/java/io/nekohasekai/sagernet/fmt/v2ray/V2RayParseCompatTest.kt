package io.nekohasekai.sagernet.fmt.v2ray

import org.junit.Assert.assertEquals
import org.junit.Test

class V2RayParseCompatTest {

    @Test
    fun queryWithSpecialCharactersParses() {
        val bean = parseV2Ray(
            "vless://00000000-0000-0000-0000-000000000001@srv.example.com:443" +
                    "?type=ws&host=a{b|c}#node"
        )

        assertEquals("srv.example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("a{b|c}", bean.host)
        assertEquals("ws", bean.type)
    }

    @Test
    fun invalidKcpHeaderTypeFallsBackToNone() {
        val bean = parseV2Ray(
            "vless://00000000-0000-0000-0000-000000000001@srv.example.com:443" +
                    "?type=kcp&headerType=banana&seed=seed1#node"
        )

        assertEquals("kcp", bean.type)
        assertEquals("none", bean.headerType)
    }

    @Test
    fun validKcpHeaderTypeKept() {
        val bean = parseV2Ray(
            "vless://00000000-0000-0000-0000-000000000001@srv.example.com:443" +
                    "?type=kcp&headerType=srtp&seed=seed1#node"
        )

        assertEquals("srtp", bean.headerType)
    }

    @Test
    fun netParameterRecognizedAsTransport() {
        val bean = parseV2Ray(
            "vless://00000000-0000-0000-0000-000000000001@srv.example.com:443" +
                    "?net=ws&path=%2Fp&host=h1#node"
        )

        assertEquals("ws", bean.type)
        assertEquals("/p", bean.path)
        assertEquals("h1", bean.host)
    }
}