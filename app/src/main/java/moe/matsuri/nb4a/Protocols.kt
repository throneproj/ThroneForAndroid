package moe.matsuri.nb4a

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NEKO
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean

// Settings for all protocols, built-in or plugin
object Protocols {

    // Deduplication

    class Deduplication(
        val bean: AbstractBean, val type: String
    ) {

        fun hash(): String {
            if (bean is ConfigBean) {
                return bean.config
            }
            // 去重键在服务器地址与端口之外纳入协议凭据与关键传输特征，
            // 避免同一服务器上凭据不同的节点被错误合并
            val sb = StringBuilder()
                .append(bean.serverAddress).append(':').append(bean.serverPort)
                .append(':').append(type)
            bean.name?.let { sb.append(":n=").append(it) }
            when (val b = bean) {
                is TrojanBean -> sb.append(":p=").append(b.password)
                    .append(":sni=").append(b.sni)

                is StandardV2RayBean -> sb.append(":u=").append(b.uuid)
                    .append(":path=").append(b.path)
                    .append(":sni=").append(b.sni)
                    .append(":rk=").append(b.realityPubKey)

                is ShadowsocksBean -> sb.append(":m=").append(b.method)
                    .append(":p=").append(b.password)
                    .append(":pl=").append(b.plugin)

                is ShadowsocksRBean -> sb.append(":m=").append(b.method)
                    .append(":p=").append(b.password)
                    .append(":pr=").append(b.protocol)
                    .append(":o=").append(b.obfs)

                is SnellBean -> sb.append(":k=").append(b.psk)
                    .append(":v=").append(b.version)

                is HysteriaBean -> sb.append(":a=").append(b.authPayloadType)
                    .append(":").append(b.authPayload)
                    .append(":o=").append(b.obfuscation)
                    .append(":ports=").append(b.serverPorts)

                is TuicBean -> sb.append(":u=").append(b.uuid)
                    .append(":t=").append(b.token)

                is JuicityBean -> sb.append(":u=").append(b.uuid)
                    .append(":p=").append(b.password)
                    .append(":sni=").append(b.sni)

                is TrojanGoBean -> sb.append(":p=").append(b.password)
                    .append(":sni=").append(b.sni)
                    .append(":path=").append(b.path)

                is NaiveBean -> sb.append(":u=").append(b.username)
                    .append(":p=").append(b.password)

                is AnyTLSBean -> sb.append(":p=").append(b.password)
                    .append(":sni=").append(b.sni)
                    .append(":rk=").append(b.realityPubKey)

                is WireGuardBean -> sb.append(":pk=").append(b.privateKey)
                    .append(":pk=").append(b.peerPublicKey)
                    .append(":psk=").append(b.peerPreSharedKey)

                is SSHBean -> sb.append(":u=").append(b.username)
                    .append(":k=").append(b.privateKey)

                is MieruBean -> sb.append(":u=").append(b.username)
                    .append(":p=").append(b.password)

                is HttpBean, is SOCKSBean -> sb.append(":u=").append(b.username)
                    .append(":p=").append(b.password)
            }
            return sb.toString()
        }

        override fun hashCode(): Int {
            return hash().toByteArray().contentHashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Deduplication

            return hash() == other.hash()
        }

    }

    // Display

    fun Context.getProtocolColor(type: Int): Int {
        return when (type) {
            TYPE_NEKO -> getColorAttr(android.R.attr.textColorPrimary)
            else -> getColorAttr(R.attr.accentOrTextSecondary)
        }
    }

    // Test

    fun genFriendlyMsg(msg: String): String {
        val msgL = msg.lowercase()
        return when {
            msgL.contains("timeout") || msgL.contains("deadline") -> {
                app.getString(R.string.connection_test_timeout_error)
            }

            msgL.contains("refused") || msgL.contains("closed pipe") -> {
                app.getString(R.string.connection_test_refused)
            }

            else -> msg
        }
    }

}
