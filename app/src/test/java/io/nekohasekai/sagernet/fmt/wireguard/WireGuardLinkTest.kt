package io.nekohasekai.sagernet.fmt.wireguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireGuardLinkTest {

    @Test
    fun parsesAwgUriWithObfuscationParams() {
        val link = "awg://priv-key-abc@1.2.3.4:51820" +
                "?publickey=pub-key-xyz&address=10.0.0.2%2F32&jc=4#awg-node"

        val beans = parseWireGuardLink(link)

        assertEquals(1, beans.size)
        val bean = beans[0]
        assertEquals("[AWG-Compat] awg-node", bean.name)
        assertEquals("priv-key-abc", bean.privateKey)
        assertEquals("1.2.3.4", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals("pub-key-xyz", bean.peerPublicKey)
        assertEquals("10.0.0.2/32", bean.localAddress)
    }

    @Test
    fun parsesWireguardUriWithoutAwgParams() {
        val link = "wireguard://priv-key-abc@5.6.7.8:51820" +
                "?publickey=pub-key&presharedkey=psk-value&address=10.0.0.2%2F32,fd00::2%2F128&mtu=1380#wg-node"

        val beans = parseWireGuardLink(link)

        assertEquals(1, beans.size)
        val bean = beans[0]
        assertEquals("wg-node", bean.name)
        assertEquals("priv-key-abc", bean.privateKey)
        assertEquals("5.6.7.8", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals("pub-key", bean.peerPublicKey)
        assertEquals("psk-value", bean.peerPreSharedKey)
        assertEquals("10.0.0.2/32\nfd00::2/128", bean.localAddress)
        assertEquals(1380, bean.mtu)
    }

    @Test
    fun parsesBase64WholeConfigFallback() {
        val conf = """
            [Interface]
            PrivateKey = priv-abc
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = pub-key
            Endpoint = 5.6.7.8:51820
        """.trimIndent()
        val link = "wireguard://" +
                java.util.Base64.getEncoder().encodeToString(conf.toByteArray())

        val beans = parseWireGuardLink(link)

        assertEquals(1, beans.size)
        assertEquals("5.6.7.8", beans[0].serverAddress)
        assertEquals(51820, beans[0].serverPort)
        assertEquals("priv-key-abc", beans[0].privateKey)
        assertEquals("pub-key", beans[0].peerPublicKey)
    }

    @Test
    fun base64ConfigWithAwgParamsGetsCompatName() {
        val conf = """
            [Interface]
            PrivateKey = priv
            Address = 10.0.0.2/32
            Jc = 4

            [Peer]
            PublicKey = pub
            Endpoint = 1.2.3.4:51820
        """.trimIndent()
        val link = "awg://" +
                java.util.Base64.getEncoder().encodeToString(conf.toByteArray())

        val beans = parseWireGuardLink(link)

        assertEquals(1, beans.size)
        assertEquals("[AWG-Compat]", beans[0].name)
    }

    @Test
    fun missingPeerPublicKeyThrowsReadableError() {
        val exception = assertThrows(IllegalStateException::class.java) {
            parseWireGuardLink("wireguard://priv@1.2.3.4:51820?address=10.0.0.2%2F32")
        }

        assert(exception.message!!.contains("missing peer public key"))
    }

    @Test
    fun invalidLinkThrowsReadableError() {
        assertThrows(IllegalStateException::class.java) {
            parseWireGuardLink("wireguard://!!!")
        }
    }
}