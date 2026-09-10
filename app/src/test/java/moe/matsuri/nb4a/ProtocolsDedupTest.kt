package moe.matsuri.nb4a

import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProtocolsDedupTest {

    private fun vmessBean(uuid: String, name: String = "node"): VMessBean {
        return VMessBean().apply {
            serverAddress = "srv.example.com"
            serverPort = 443
            this.uuid = uuid
            this.name = name
        }
    }

    @Test
    fun sameServerDifferentUuidAreDistinct() {
        val d1 = Protocols.Deduplication(vmessBean("uuid-1"), VMessBean().javaClass.toString())
        val d2 = Protocols.Deduplication(vmessBean("uuid-2"), VMessBean().javaClass.toString())

        assertNotEquals(d1, d2)
    }

    @Test
    fun identicalNodesAreMerged() {
        val type = VMessBean().javaClass.toString()
        val d1 = Protocols.Deduplication(vmessBean("uuid-1"), type)
        val d2 = Protocols.Deduplication(vmessBean("uuid-1"), type)

        assertEquals(d1, d2)
        assertEquals(d1.hashCode(), d2.hashCode())
    }

    @Test
    fun sameServerDifferentNameAreDistinct() {
        val type = VMessBean().javaClass.toString()
        val d1 = Protocols.Deduplication(vmessBean("uuid-1", "node-a"), type)
        val d2 = Protocols.Deduplication(vmessBean("uuid-1", "node-b"), type)

        assertNotEquals(d1, d2)
    }

    @Test
    fun trojanSameServerDifferentPasswordAreDistinct() {
        val type = TrojanBean().javaClass.toString()
        fun trojan(password: String): TrojanBean {
            return TrojanBean().apply {
                serverAddress = "srv.example.com"
                serverPort = 443
                this.password = password
                name = "node"
            }
        }

        val d1 = Protocols.Deduplication(trojan("pass-1"), type)
        val d2 = Protocols.Deduplication(trojan("pass-2"), type)

        assertNotEquals(d1, d2)
    }

    @Test
    fun vlessSameServerDifferentPathAreDistinct() {
        val type = VMessBean().javaClass.toString()
        fun vless(path: String): VMessBean {
            return vmessBean("uuid-1").apply { this.path = path }
        }

        val d1 = Protocols.Deduplication(vless("/path-a"), type)
        val d2 = Protocols.Deduplication(vless("/path-b"), type)

        assertNotEquals(d1, d2)
    }
}