package moe.matsuri.nb4a

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ktx.deduplicateProxies
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
    fun sameNameDifferentUuidAreDistinct() {
        val type = VMessBean().javaClass.toString()
        val d1 = Protocols.Deduplication(vmessBean("uuid-1", "node-a"), type)
        val d2 = Protocols.Deduplication(vmessBean("uuid-2", "node-a"), type)

        assertNotEquals(d1, d2)
    }

    @Test
    fun differentNamesSameCredentialsMerge() {
        // 节点名不参与去重哈希：同名不同凭据保留，不同名同凭据合并
        val type = VMessBean().javaClass.toString()
        val d1 = Protocols.Deduplication(vmessBean("uuid-1", "node-a"), type)
        val d2 = Protocols.Deduplication(vmessBean("uuid-1", "node-b"), type)

        assertEquals(d1, d2)
        assertEquals(d1.hashCode(), d2.hashCode())
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

    private fun hysteriaBean(ports: String, auth: String = "auth-1"): HysteriaBean {
        return HysteriaBean().apply {
            serverAddress = "srv.example.com"
            serverPort = 443
            serverPorts = ports
            protocolVersion = 2
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = auth
            name = "node"
        }
    }

    @Test
    fun hysteriaDifferentFirstPortAreDistinct() {
        val type = HysteriaBean().javaClass.toString()
        val d1 = Protocols.Deduplication(hysteriaBean("443"), type)
        val d2 = Protocols.Deduplication(hysteriaBean("8443"), type)

        assertNotEquals(d1, d2)
    }

    @Test
    fun hysteriaSameFirstPortDifferentRangeSuffixMerge() {
        // 多端口节点取首个端口：首端口相同即视为同一节点
        val type = HysteriaBean().javaClass.toString()
        val d1 = Protocols.Deduplication(hysteriaBean("443"), type)
        val d2 = Protocols.Deduplication(hysteriaBean("443,9000-9100"), type)

        assertEquals(d1, d2)
    }

    @Test
    fun deduplicateProxiesMergesIdenticalKeepsDistinct() {
        val a = vmessBean("uuid-1", "name-a")
        val b = vmessBean("uuid-1", "name-b") // 同凭据不同名 → 合并
        val c = vmessBean("uuid-2", "name-a") // 同名不同凭据 → 保留
        val d = vmessBean("uuid-2", "name-a").apply { serverPort = 8443 } // 不同端口 → 保留

        val result = listOf(a, b, c, d).deduplicateProxies()

        assertEquals(3, result.size)
        assertEquals(a, result[0])
        assertEquals(c, result[1])
        assertEquals(d, result[2])
    }
}