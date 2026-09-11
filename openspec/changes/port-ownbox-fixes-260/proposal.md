# Proposal: port-ownbox-fixes-260

## Why

姊妹分支 OwnBoxForAndroid 在 v2.4.0 → v2.6.0 迭代中又积累了一批与当前 `SINGBOX_VERSION=v1.13.16` 兼容的稳定性、订阅健壮性、IPv6 泄露防护与主题外观修复（详见 `ROO_OWN_RESEARCH.md` 第 0 章）。T4A 当前仍存在同型缺陷：批量更新订阅时单个失败会取消整批、订阅 diff 无熔断保护（机场抽风可清空全部节点）、剪贴板导入无条件强制去重、禁用 IPv6 时存在流量与 DNS 泄露路径、数据库打开失败直接删库无备份、Fragment detached 时 snackbar 崩溃。按用户批准范围（调研报告 0.8 节 P0 的 1~11、14 + 协议相关修复中与 sing-box 1.14 升级无关的可兼容项 + 主题色菜单加入纯白模式）将这些修复移植回 T4A。

## What Changes

- **数据库稳定性增强**（`SagerDatabase`）：数据库打开失败兜底从"直接删库重建"升级为"**先备份原库为 `.bak_<timestamp>` 再删库重建**"，降低数据丢失面。不引入对端 Room v10（`balancerBean` 列）——负载均衡不在本次范围。
- **IPv6 泄露修复全套**（`bg/VpnService.kt` + `fmt/ConfigBuilder.kt`）：
  - `VpnService`：即使 IPv6Mode.DISABLE 也始终添加 IPv6 虚拟地址（/126）与路由（`2000::/3` 或 `::/0`，另加 `fc00::/7`），让系统完整接管 IPv6，杜绝物理网卡旁路泄露。
  - `ConfigBuilder`（ipv6Mode == DISABLE 时）：`dns.strategy = "ipv4_only"`、`autoDnsDomainStrategy` 强制 `ipv4_only`、链路 `defaultServerDomainStrategy` 强制 `ipv4_only`；DNS 规则头部插入 `query_type=["AAAA"] action=reject`；route 规则头部插入 `ip_version=6 action=reject`；fakeip 不再生成 `inet6_range`，fakeip 规则 `query_type` 只保留 `A`；tun `address` 不再因 DISABLE 而省略 IPv6 虚拟地址。
- **批量订阅更新并发化与防闪退**（`group/GroupUpdater.kt` + `ui/GroupFragment.kt`）：
  - `GroupUpdater.executeUpdate`：`coroutineScope` → `supervisorScope`（单个订阅失败不再取消整批）；`subscription` 判空防 NPE；后台批量更新失败不再弹 UI（仅用户手动触发时报错）；重复触发改为返回 false 而非 cancel 抛异常。
  - `GroupFragment` 批量更新：改为 `supervisorScope + async/awaitAll` 并发更新全部订阅，逐个 try-catch，汇总成功/失败数。
- **RawUpdater 订阅 diff 健壮化**：
  - **熔断器**：`exists >= 10 && fetched < exists * 70%` 时跳过删除（防机场抽风清空节点）。
  - diff 改为按顺序匹配（`indexOfFirst` + 移除），保持 userOrder 稳定；插入改为先收集 `toInsert` 再逐条入库。
  - 下载失败回退：连接状态下代理拉取失败自动转**直连**重试（每个 UA 候选内）。
  - 新增 `Profile-Title`/`X-Profile-Title` 响应头捕获（含 `base64:` 前缀解码）。
- **剪贴板导入去重修正**（`ui/MainActivity.kt` + `ktx/Formats.kt`）：仅当目标分组（或当前分组）`subscription.deduplication == true` 时才执行去重（修复强制去重缺陷）；新增 `dedupKey()`/`deduplicateProxies()` 扩展函数（基于现有 `Protocols.Deduplication`）。
- **节点去重键重构**（`moe/matsuri/nb4a/Protocols.kt`）：dedup key 从 name 参与哈希改为按协议字段（uuid/password/sni/pubKey/privateKey/peerPublicKey/localAddress/credentials 等）+ `finalPort`/Hysteria 首端口——同服务器不同凭据不再误合并、同名不同凭据不再漏合并。
- **协议连通性修复**（仅吸纳与 sing-box 1.14 升级无关的可兼容项）：
  - `HysteriaFmt.kt`：`getFirstPort` 兼容 `port-range`（`-` 分隔）；SNI 为空回退 `serverAddress`；h2 分支 alpn 改为从 bean 解析（不再硬编码 h3）；`udp_fragment = true`。**不移植** `hopPortsToSingboxList` 输出格式改为 `start:end` 的变更（该格式为 sing-box 1.14 语义，T4A 维持 1.13.16 的 `start-end`）。
  - `TuicFmt.kt`：SNI 空回退 serverAddress + 尊重 `disableSNI`；`udp_fragment = true`。
  - `ShadowsocksFmt.kt`：v2ray-plugin 强制补 `mux=0`；空 plugin_opts 置 null。
  - `V2RayFmt.kt`：链接 query 含 `{ } " 空格 | \ ^ < >` 时逐字符 percent-encode 后重试解析，再退 `java.net.URI`；`net=` 参数识别；kcp `headerType` 非法值回退 `none`（不再 error）；`extra` xhttp 转换失败回退原文。**不移植** Trojan 强制 TLS 与 `server_name` 空回退（需按 1.13.16 schema 逐项验证，见 design 决策 4）。
- **UI 防崩溃杂项**（`ktx/Utils.kt` 等）：`Fragment.snackbar` 改为安全解析（`MessageStore.getCurrentActivity()` 回退 + `safeSnackbar` Toast 兜底，修复批量更新时 Fragment detached 闪退）；`getColorAttr` 容错（attribute 缺失/解析失败返回透明色）；`BackupFragment`/`GroupFragment` 导出改用安全 resolver；`ToolbarFragment` toolbar 判空。
- **主题色菜单加入纯白模式**（原样移植）：`Theme` 新增 `WHITE` 主题（含 `isWhiteTheme()`、`getPrimaryColor()`）；`ColorPickerPreference` 预设色板加入纯白选项；`ThemedActivity`/`ToolbarFragment`/`MainActivity`（FAB 反色）适配纯白模式外观。不移植对端 `CUSTOM` 自定义色值、HSV/RGB 滑条重写（未批准）。
- **约束**：所有新增/修改代码的注释不得出现 "OwnBox" 字样（沿用 `tools/diagnostics/check_no_brand_comments.py` 把关）。
- **明确排除**（按兵不动）：URL 测速链路全部改动（fallback URL 互备、`>=400` 状态码判定、`urlTestCustomUrl` AIDL、两阶段 Keep-Alive 重写——T4A 维持现行双 HEAD 预热方案与 200 判定）、TcpPing 修复（UDP-only 回退、DNS 预解析）、BalancerBean 独立负载均衡节点、机场名自动提取、订阅资产信息卡片、每组 URLTest URL、lockUserAgent、分组名作为 outbound tag、ConnectivityTestActivity、多规格 Widget、自定义主题色 CUSTOM 模式、设置页手风琴、媒体解锁重构、START_STICKY/亮屏 wake/WifiLock、品牌/更名/图标精简、versionCode metadata 动态读取。

## Capabilities

### New Capabilities

（无——本变更不引入新能力域。）

### Modified Capabilities

- `android-application`: 配置档数据库 MUST 在打开失败时先备份原库再重建；批量订阅更新 MUST 并发执行且单个失败不影响其余订阅、后台失败 MUST NOT 弹 UI；订阅 diff MUST 在节点数骤降时熔断跳过删除并保持节点顺序稳定；订阅下载在代理路径失败时 MUST 回退直连重试；剪贴板导入 MUST 仅在目标订阅启用去重时去重；节点去重键 MUST 按协议凭据特征计算且不依赖节点名；Fragment 提示 MUST 在宿主不可用时安全降级。
- `theme-system`: 预设主题色选择器 MUST 提供纯白模式选项；纯白模式启用时应用主要界面（工具栏、FAB、系统栏）MUST 呈现纯白外观且保持图标/文字可读性。

## Impact

- **Android 应用层**：`app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt`、`bg/VpnService.kt`、`group/GroupUpdater.kt`、`group/RawUpdater.kt`、`ui/GroupFragment.kt`、`ui/MainActivity.kt`、`ktx/Formats.kt`、`ktx/Utils.kt`、`fmt/hysteria/HysteriaFmt.kt`、`fmt/tuic/TuicFmt.kt`、`fmt/shadowsocks/ShadowsocksFmt.kt`、`fmt/v2ray/V2RayFmt.kt`、`moe/matsuri/nb4a/Protocols.kt`、`utils/Theme.kt`、`database/DataStore.kt`。
- **资源**：`values/themes.xml`（纯白主题 style）、`values/colors.xml`、`values/strings.xml` 及 `values-zh-rCN/-rHK/-rTW` 等语言目录的纯白模式文案；Room schema 导出（`app/schemas/`，仅当实体注解变化时）。
- **不涉及**：libcore Go 代码、`nb4a.properties` 的 `SINGBOX_VERSION`（保持 v1.13.16）、URL 测速链路（`libcore/box.go`、`bg/proto/UrlTest.kt`、`SpeedTestRunner`）、TCP Ping（`bg/proto/TcpPing.kt`）、GitHub workflows 布局。
- **外部依赖参考**：sing-box 官方 v1.13.16 schema（https://github.com/SagerNet/sing-box ，tag v1.13.16）用于验证 Hysteria `udp_fragment`、TUIC `udp_fragment`、SS plugin_opts 等字段兼容性；Android `VpnService.Builder` addAddress/addRoute 官方文档用于 IPv6 路由行为。
- **验证方式**：本地仅静态校验与单元测试（扩展 `HysteriaFmtTest`、新增去重/解析测试）；编译与真机验证交由 GitHub Actions 与用户真机。
