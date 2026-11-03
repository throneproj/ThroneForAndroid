package io.nekohasekai.sagernet.ui.profile

import android.content.Context
import android.content.Intent
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.outbound.OutboundFactory

/** The editor activity of a desktop profile type string ("custom" and unknown types open the raw JSON editor). */
fun settingsActivityFor(type: String): Class<out ProfileSettingsActivity<*>> =
    when (OutboundFactory.canonicalType(type)) {
        "socks" -> SocksSettingsActivity::class.java
        "http" -> HttpSettingsActivity::class.java
        "shadowsocks" -> ShadowsocksSettingsActivity::class.java
        "vmess" -> VMessSettingsActivity::class.java
        "vless" -> VlessSettingsActivity::class.java
        "xrayvless" -> XrayVlessSettingsActivity::class.java
        "trojan" -> TrojanSettingsActivity::class.java
        "hysteria" -> HysteriaSettingsActivity::class.java
        "tuic" -> TuicSettingsActivity::class.java
        "juicity" -> JuicitySettingsActivity::class.java
        "anytls" -> AnyTLSSettingsActivity::class.java
        "shadowtls" -> ShadowTLSSettingsActivity::class.java
        "ssh" -> SSHSettingsActivity::class.java
        "naive" -> NaiveSettingsActivity::class.java
        "mieru" -> MieruSettingsActivity::class.java
        "snell" -> SnellSettingsActivity::class.java
        "wireguard" -> WireGuardSettingsActivity::class.java
        "trusttunnel" -> TrustTunnelSettingsActivity::class.java
        "dnstt" -> DnsttSettingsActivity::class.java
        "masterdns" -> MasterDnsSettingsActivity::class.java
        "direct" -> DirectSettingsActivity::class.java
        "masque" -> MasqueSettingsActivity::class.java
        "openvpn" -> OpenVpnSettingsActivity::class.java
        "openconnect" -> OpenConnectSettingsActivity::class.java
        "chain" -> ChainSettingsActivity::class.java
        "autoselector" -> AutoSelectorSettingsActivity::class.java
        else -> CustomSettingsActivity::class.java
    }

fun ProxyEntity.profileSettingsIntent(ctx: Context, isSubscription: Boolean): Intent =
    Intent(ctx, settingsActivityFor(type)).apply {
        putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, id)
        putExtra(ProfileSettingsActivity.EXTRA_IS_SUBSCRIPTION, isSubscription)
    }
