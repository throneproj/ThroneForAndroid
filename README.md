# Throne for Android (Formerly NekoBox for Android)

[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

## 使用前须知

> 免责声明：本项目仅用于技术研究与代码学习之目的，不提供任何形式的网络代理服务。请勿将本项目用于违反当地法律法规的任何活动。请勿在生产环境中使用本项目，使用者应自行承担使用本项目可能带来的全部风险。若您下载或引用本项目，请在 24 小时内自行删除相关内容，并避免长期存储、分享或传播本项目的任何部分。**作者保留随时修改、更新或移除本项目及其内容的权利，恕不另行通知。**
> 
> Disclaimer: This project is intended solely for technical research and code learning purposes and does not provide any form of network proxy service. Please do not use this project for any activities that violate local laws and regulations. Do not use this project in production environments. Users are fully responsible for any risks that may arise from using this project. If you download or reference this project, please delete all related content within 24 hours and avoid long-term storage, distribution, or dissemination of any part of this project. **The author reserves the right to modify, update, or remove any part of this project or its contents at any time without prior notice.**

## 下载 / Downloads

[![GitHub All Releases](https://img.shields.io/github/downloads/throneproj/ThroneForAndroid/total?label=downloads-total&logo=github&style=flat-square)](https://github.com/throneproj/ThroneForAndroid/releases)

[GitHub Releases 下载](https://github.com/throneproj/ThroneForAndroid/releases)

> 1.7.0 is signed with a new key and starts with a fresh database: uninstall older versions first, then add your subscriptions again.
> 1.7.0 使用新的签名密钥，数据库也会重新开始：请先卸载旧版本，再重新添加订阅。

## 交流反馈 / Feedback

https://github.com/throneproj/ThroneForAndroid/issues

## 项目主页 & 文档 / Homepage & Documents

https://throneproj.github.io

## Features

* Groups and subscriptions that work like Throne desktop: per-group subscription options (filters, clean-up,
  URL test after an update), front / landing proxy, HWID headers, scheduled updates with change reports
* Backups in the desktop's `.thrbackup` format, restorable on either side: save, share or sync over WebDAV
* Auto selector profiles that keep the best server of a group ranked and switch automatically
* Cloudflare WARP: registration (WireGuard or MASQUE identity) and a built-in WARP mode for routing
* URL, IP / country and speed tests, with a bulk-test panel (live progress, latency histogram, fastest servers,
  sort / remove unavailable / connect to fastest)
* The sing-box dashboard built in (live traffic, connections, logs), already connected to the running core
* Xray configs with geoip: / geosite: rules: the data files are downloaded when a config first needs them
* JSON editor with sing-box schema checking, completion and formatting
* In-app updater for GitHub builds (stable or pre-release channel, verified downloads)
* Home-screen widgets, and server switching from the notification
* Route rules by Wi-Fi network (SSID / BSSID)
* Android TV: launcher banner, D-pad navigation, previous / next server

## 支持的代理协议 / Supported Proxy Protocols

* SOCKS (4/4a/5)
* HTTP(S)
* Shadowsocks
* VMess
* VLESS (sing-box 与 Xray / sing-box and Xray, incl. XHTTP, REALITY)
* Trojan
* Hysteria 1/2 (incl. realm)
* TUIC
* Juicity
* AnyTLS
* ShadowTLS
* SSH
* NaïveProxy
* Mieru
* Snell v4/v6
* WireGuard / AmneziaWG
* TrustTunnel
* MASQUE
* OpenVPN
* OpenConnect
* 代理链 / Chain
* 自定义 JSON / Custom JSON

<details>
<summary>XHTTP Extra TLS配置示例</summary>

<pre><code class="language-json">
{
    "no_grpc_header": false,  // stream-up/one
	"x_padding_bytes": "100-10000",
	"sc_max_each_post_bytes": 1000000, // packet-up only
	"sc_min_posts_interval_ms": 30, // packet-up only
	"xmux": {
		"max_concurrency": "16-32",
		"max_connections": "0-0",
		"c_max_reuse_times": "0-0",
		"h_max_request_times": "600-900",
		"h_max_reusable_secs": "1800-3000",
		"h_keep_alive_period": 0
	},
    "x_padding_obfs_mode": false,
    "x_padding_key": "",
    "x_padding_header": "",
    "x_padding_placement": "",
    "x_padding_method": "",
    "uplink_http_method": "",
    "session_placement": "",
    "session_key": "",
    "seq_placement": "",
    "seq_key": "",
    "uplink_data_placement": "",
    "uplink_data_key": "",
    "uplink_chunk_size": 0,
	"download": {
		"mode": "auto",
		"host": "b.yourdomain.com",
		"path": "/xhttp",
        "no_grpc_header": false,  // stream-up/one
	    "x_padding_bytes": "100-10000",
	    "sc_max_each_post_bytes": 1000000, // packet-up only
	    "sc_min_posts_interval_ms": 30, // packet-up only
		"xmux": {
			"max_concurrency": "16-32",
			"max_connections": "0-0",
			"c_max_reuse_times": "0-0",
			"h_max_request_times": "600-900",
			"h_max_reusable_secs": "1800-3000",
			"h_keep_alive_period": 0
		},
        "x_padding_obfs_mode": false,
        "x_padding_key": "",
        "x_padding_header": "",
        "x_padding_placement": "",
        "x_padding_method": "",
        "uplink_http_method": "",
        "session_placement": "",
        "session_key": "",
        "seq_placement": "",
        "seq_key": "",
        "uplink_data_placement": "",
        "uplink_data_key": "",
        "uplink_chunk_size": 0,
		"server": "$(ip_or_domain_of_your_cdn)",
		"server_port": 443,
		"tls": {
			"enabled": true,
			"server_name": "b.yourdomain.com",
			"alpn": "h2",
			"utls": {
				"enabled": true,
				"fingerprint": "chrome"
			}
		}
	}
}
</code></pre>
</details>

<details>
<summary>XHTTP Extra Reality配置示例</summary>

<pre><code class="language-json">
{
    "no_grpc_header": false,  // stream-up/one
	"x_padding_bytes": "100-10000",
	"sc_max_each_post_bytes": 1000000, // packet-up only
	"sc_min_posts_interval_ms": 30, // packet-up only
	"xmux": {
		"max_concurrency": "16-32",
		"max_connections": "0-0",
		"c_max_reuse_times": "0-0",
		"h_max_request_times": "600-900",
		"h_max_reusable_secs": "1800-3000",
		"h_keep_alive_period": 0
	},
    "x_padding_obfs_mode": false,
    "x_padding_key": "",
    "x_padding_header": "",
    "x_padding_placement": "",
    "x_padding_method": "",
    "uplink_http_method": "",
    "session_placement": "",
    "session_key": "",
    "seq_placement": "",
    "seq_key": "",
    "uplink_data_placement": "",
    "uplink_data_key": "",
    "uplink_chunk_size": 0,
	"download": {
		"mode": "auto",
		"host": "example.com",
		"path": "/xhttp",
        "no_grpc_header": false,  // stream-up/one
	    "x_padding_bytes": "100-10000",
	    "sc_max_each_post_bytes": 1000000, // packet-up only
	    "sc_min_posts_interval_ms": 30, // packet-up only
		"xmux": {
			"max_concurrency": "16-32",
			"max_connections": "0-0",
			"c_max_reuse_times": "0-0",
			"h_max_request_times": "600-900",
			"h_max_reusable_secs": "1800-3000",
			"h_keep_alive_period": 0
		},
        "x_padding_obfs_mode": false,
        "x_padding_key": "",
        "x_padding_header": "",
        "x_padding_placement": "",
        "x_padding_method": "",
        "uplink_http_method": "",
        "session_placement": "",
        "session_key": "",
        "seq_placement": "",
        "seq_key": "",
        "uplink_data_placement": "",
        "uplink_data_key": "",
        "uplink_chunk_size": 0,
		"server": "$(ip_or_domain_of_your_cdn)",
		"server_port": 443,
		"tls": {
			"enabled": true,
			"server_name": "example.com",
			"reality": {
				"enabled": true,
				"public_key": "$(your_publicKey)",
				"short_id": "$(your_shortId)"
			},
			"utls": {
				"enabled": true,
				"fingerprint": "chrome"
			}
		}
	}
}
</code></pre>
</details>

## 支持的订阅格式 / Supported Subscription Format

* 一些广泛使用的格式 (如 Shadowsocks, ClashMeta 和 v2rayN)
* sing-box 订阅格式

仅支持解析出站，即节点。分流规则等信息会被忽略。

* Some widely used formats (like Shadowsocks, ClashMeta and v2rayN)
* sing-box outbound

Only resolving outbound, i.e. nodes, is supported. Information such as diversion rules are ignored.

## Credits

Core: [ThroneCore](https://github.com/throneproj/Throne), built from the `THRONE_CORE_REF` commit pinned in `nb4a.properties`（核心版本以 `nb4a.properties` 中的 `THRONE_CORE_REF` 为准）:

- [throneproj/sing-box](https://github.com/throneproj/sing-box), a fork of [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [throneproj/Xray-core](https://github.com/throneproj/Xray-core), a fork of [XTLS/Xray-core](https://github.com/XTLS/Xray-core)
- [Mahdi-zarei/speedtest-go](https://github.com/Mahdi-zarei/speedtest-go)

Android GUI:

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

## Star History

<a href="https://www.star-history.com/?repos=throneproj%2FThroneForAndroid&type=date&legend=bottom-right">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=throneproj/ThroneForAndroid&type=date&theme=dark&legend=bottom-right&sealed_token=vVh7Hn3UTnDoalet423u1x-LDNiIZQ2VfWY7GGmbMR8V-4feGR0yTT_IpVxBrxSTOwF7xFnsTcZjyHqNufLeTmdL5f-lw36iYWXcJSlXuJwapM1s8wChkg" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=throneproj/ThroneForAndroid&type=date&legend=bottom-right&sealed_token=vVh7Hn3UTnDoalet423u1x-LDNiIZQ2VfWY7GGmbMR8V-4feGR0yTT_IpVxBrxSTOwF7xFnsTcZjyHqNufLeTmdL5f-lw36iYWXcJSlXuJwapM1s8wChkg" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=throneproj/ThroneForAndroid&type=date&legend=bottom-right&sealed_token=vVh7Hn3UTnDoalet423u1x-LDNiIZQ2VfWY7GGmbMR8V-4feGR0yTT_IpVxBrxSTOwF7xFnsTcZjyHqNufLeTmdL5f-lw36iYWXcJSlXuJwapM1s8wChkg" />
 </picture>
</a>
