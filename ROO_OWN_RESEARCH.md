# OwnBox (OwnBoxForAndroid) 深度调研报告

> 调研对象：`C:\repos\OwnBoxForAndroid`（origin: https://github.com/Own716/OwnBoxForAndroid ）
> 基准仓库：`C:\repos\ThroneForAndroid`（T4A，main @ `48547d2`，v1.6.2，sing-box v1.13.16）
> 报告生成时间：调研于 T4A main @ `48547d2`（2026-09-04 "bump version"）

---

## 1. 调研方法与基线说明（重要）

直接用 `git merge-base` 得到的分叉点是 `5768494`（T4A v1.4.2，2026-02-09），但这**不是有效的对比基线**：

- OwnBox 从 T4A v1.4.2 分叉后，其首个提交 `e541277`（2026-09-06）就把 T4A 后续约 560 个提交的成果整体移植了进来（提交信息自称 "port all Throne features"）。
- 因此 `git diff 5768494..OwnBox-HEAD` 会把 T4A 自己的工作（openspec/、tools/diagnostics/、SpeedTestRunner、ThroneDesktopBackupImporter、XHTTP 移植等）误算成 OwnBox 的改动。

**本报告采用的正确基线**：`git diff t4a/main(48547d2) OwnBox-HEAD(3769c07)`，即 OwnBox 在"移植完 T4A 2026-09-04 状态"之后**真正属于自己的增量**：

```
172 files changed, 9240 insertions(+), 479 deletions(-)
```

OwnBox 当前版本：v2.3.10（versionCode 240），包名 `com.ownbox.app`，sing-box **v1.14.0**。
OwnBox 独有提交共 63 个（其中约 12 个为 README/品牌/捐赠类，无代码价值）。

---

## 2. 改动全景分类

### A. 内核层（libcore，Go）—— sing-box 1.14 升级及适配 ⭐ 最大差异

T4A 仍在 sing-box **v1.13.16**，OwnBox 已升级到 **v1.14.0** 并完成全套 API 适配：

| 文件 | 改动内容 |
|---|---|
| `libcore/go.mod` | 依赖整体升级到 sing-box 1.14 生态（sing v0.6.x 系、grpc 1.79 等） |
| `libcore/box.go` | `ResetNetwork(ctx)` 新签名；注册 `CertificateProviderRegistry`；**urlTest 重写**：从"双 HEAD 预热+复用连接"改为"单次真实握手计时"（`DisableKeepAlives` + `InsecureSkipVerify`），理由是二次请求会导致部分节点连接重置、结果虚高 |
| `libcore/platform_box.go` | 1.14 平台接口适配：新增 `ProcessPlatformOptions`、`CancelNotification`、`ReadWIFIState(ctx)`、Neighbor/Shell/Bridge/Tailscale 系列空实现 |
| `libcore/box_include.go` | 注册自定义 `loadbalance` outbound |
| `libcore/protocol/loadbalance/outbound.go` | **新增 143 行**：自定义负载均衡分组 outbound（基于 sing-box group API 扩展） |
| `libcore/ruleset.go` | 适配 1.14 中 `RuleSet.Tag` 类型变化（`[]string` → 取首元素） |
| `libcore/protocol/vless/xhttp/client.go` | 修复 `qtls.Dial` 新签名（移除 `bufio.NewUnbindPacketConn` 包装） |
| `libcore/dns_box.go` | 小幅适配 |

### B. 配置生成层（ConfigBuilder / SingBoxOptions）—— sing-box 1.14 DNS schema 适配 ⭐

提交 `a5c0685`（+209/-67）是移植 1.14 的关键配套：

- **`DNSServerOptions` 结构重写**：旧 `address/address_resolver/address_strategy/address_fallback_delay` → 新 `type/server/server_port/path/domain_resolver/domain_strategy`（`SingBoxOptions.java`）。
- **新增 `buildDnsServer()` 工厂函数**（ConfigBuilder.kt，约 130 行）：把 `tls://`、`quic://`、`h3://`、`https://`、`tcp://`、`local`、`hosts` 等旧式 address 字符串解析为新式 typed DNS server，非 IP 地址自动补 `domain_resolver/domain_strategy`。
- **FakeDNS 迁移**：1.14 移除顶层 `dns.fakeip`，改为 `dns.servers` 中 `type: "fakeip"` 的 server（含 `inet4_range/inet6_range`）。
- **DNS 规则**：`dns-block` server 改为 `action: "reject"`（1.14 移除了 block server 类型）；`DNSRule_DefaultOptions` 增加 `action/rcode` 字段。
- **TLS 分片重构**：不再生成独立 fragment outbound，改为在主代理 outbound 上挂 `detour` + 按逗号/区间解析 `fragmentInterval` 首值计算 delay（详见 `74e97e7`）。

### C. 订阅与节点解析修复（RawUpdater / Formats / Util）⭐ 高价值

1. **多 UA 回退下载**（`c6142f6` + `74e97e7`，RawUpdater.kt）：
   - 候选 UA 链：用户自定义 UA → `Singbox/1.14` → `clash-meta` → `v2rayN/7.8.2` → `Throne/1.0.0` → `sing-box/1.14.0`，逐个尝试直到解析出非空节点；记录最后一次成功的 `Subscription-Userinfo` 与 `content-disposition`。
   - 下载客户端移除 `tryH3Direct()`（该模式在部分服务器上失败）。
2. **`parseProxies` 重构**（Formats.kt）：
   - 按协议 scheme（ss/vmess/vless/trojan/hysteria2/tuic/snell/anytls/awg 等 18 种）智能切分同一行多个链接，替代无脑按空格切分；
   - 单链接文本中的 `clash://`/`sn://subscription` 才抛 `SubscriptionFoundException`，混合内容不再误判；
   - 行解析结果优先（`entitiesByLine.size >= entities.size` 时取行解析）。
3. **Base64 解码健壮化**（Util.kt `b64Decode`）：trim、去空白/换行、自动补 padding、URL_SAFE flag、cleaned/padded 双轮尝试。
4. **YAML 解析容错**（RawUpdater.kt）：clash proxies 逐条 try-catch（单条坏节点不再毁掉整个订阅）、type 小写化、空结果继续走后续解析分支；WireGuard `[Interface]` 同样空结果不提前 return。
5. **节点去重键增强**（Protocols.kt）：`ProxyEntity` 的 dedup key 从 `serverAddress+serverPort+type` 扩展为包含 uuid/password/path/sni/realityPubKey/name 等，避免同服务器不同凭据的节点被错误合并。
6. **链接解析修复**：
   - `V2RayFmt.kt`/`TrojanFmt.kt`：fragment 先剥离并 URL-decode 作为节点名（修复含 `#` 的链接解析失败/名字乱码）；`toHttpUrl()` → `toHttpUrlOrNull()` + 显式 error（修复畸形链接直接崩溃）；Kitsunebi 分支同样修复。
   - `SnellFmt.kt`：非标准 snell:// 链接的 regex 回退解析（psk@host:port 及 query 参数多别名）。
   - `WireGuardFmt.kt`：**新增 `parseWireGuardLink()`**（61 行）——支持 `wireguard://`/`awg://` URI（含 base64 整配置回退、AWG 混淆参数 Jc/S1/H1 识别、`[AWG-Compat]` 前缀）；`parseWireGuardConfig` 增加 AWG 检测。
   - `HysteriaFmt.kt` `hopPortsToSingboxList`：端口区间格式规范化（`:`→`-`、trim、数字校验），修复畸形端口列表生成非法配置。

### D. 稳定性 / 崩溃修复 ⭐ 高价值低成本

| 提交 | 内容 |
|---|---|
| `f231a46` | Room：`ssrBean`/`snellBean` 列加 `@ColumnInfo(defaultValue = "NULL")`，修复升级迁移崩溃；`SagerDatabase` 初始化加 **try-open → 失败删库重建** 兜底 + `fallbackToDestructiveMigrationOnDowngrade()` |
| `2481d78` | MediaUnlock/TrafficChart/StatsBar：移除不存在的 `statusCode` 调用、`forEachLine` 返回值误用、补 `isActive` import |
| `614d7ec`/`5d272b3` | `SubscriptionUserAgentPreference` 崩溃修复（MaterialComponents 主题上下文、Logs import） |
| `a5c0685` | MaterialCardView 崩溃修复（themes.xml 增加 `cardStroke` 等兼容 attr，values-v26 主题） |
| `3b5b35c`/`047be8e`/`b95895a`/`fa70920`/`6db9d67` | 缺失 drawable/字符串资源、XML 声明、重复资源清理 |
| `8643e92` | modernTLS imports、YouTube view binding 命名、HTTP response 处理 |
| `578cc59`/`d182069`/`90a5b06` | suspend 函数、snackbar、`toBase64Str`、协程 scope、TileService `setSubtitle` 等编译期修复 |

### E. 新功能（OwnBox 自有）

1. **TCP Ping**（`bg/proto/TcpPing.kt`，45 行）：纯 TCP connect 延迟测试（绑定 underlyingNetwork + protect socket），作为 URLTest 之外的快速测速。
2. **负载均衡分组**：`DataStore.groupIsLoadBalance`（每组开关）+ ConfigBuilder `buildLoadBalanceOutbound()` + libcore `loadbalance` outbound + GroupSettingsActivity/ConfigurationFragment UI。
3. **每分组 URLTest 细化选项**（T4A 完全没有）：`groupUrlTestInterval/Tolerance/IdleTimeout/InterruptExist/Url`（每组独立存储）、`groupDisabled` 开关；默认测试 URL 从 gstatic 迁移到 `http://cp.cloudflare.com/generate_204`（含旧值自动迁移）。
4. **订阅 UA 设置**：`SubscriptionUserAgentPreference`（对话框 UI + `layout_dialog_user_agent.xml`），默认 UA `Singbox/1.14`，`DataStore.migrateSubscriptionUserAgents()` 批量迁移。
5. **落地 IP（Landing IP）**：`LandingIpManager.kt`（181 行）+ StatsBar 集成（+201 行）+ `LandingIpBottomSheet` + `layout_landing_ip_details.xml`——显示出口/落地 IP 详情。
6. **实时流量图表**：`TrafficChartActivity`（497 行）+ `TrafficChartView`（188 行自定义 View），经 Clash API 拉取实时速率绘图。
7. **网络工具**：`IpPurityActivity`（IP 纯度检测，575 行布局）、`MediaUnlockActivity`（流媒体解锁检测，587 行，含 ChatGPT/Claude/Disney 等平台图标）。
8. **节点选择对话框**：`NodeSelectDialogActivity`（widget 用的单实例悬浮节点切换）。
9. **桌面 Widget**：`OwnBoxWidgetProvider` + `ownbox_widget_info.xml` + iOS 26 "liquid glass" 风格背景 drawable 组（glass/capsule 明暗全套），支持点击开关、切换节点。
10. **应用图标主题**：`AppIcon.kt`/`AppIconManager.kt`/`AppIconDialog.kt` + 15 个 `activity-alias`（原版/明暗/原神角色等）+ `AppListActivity` 连接应用图标。
11. **其他**：AMOLED 纯黑主题（`Theme.GREEN` 默认 + amoledTheme 开关）、`CountryFlagUtils`（节点国旗）、`BackupHelper` 扩展（+90 行，备份新设置项）、`ThemedActivity` 基类小扩展。
12. **测试**：`SnellFmtTest.kt`（94 行）。

### F. CI / 构建

- `buildScript/lib/assets.sh`（`4d73b6c`）：geoip/geosite 版本获取改为 **releases/latest 302 重定向解析**（绕过 GitHub API 限流），失败回退 `GITHUB_TOKEN` API / `latest/download` 直链。
- `buildSrc Helpers.kt`：release 签名兜底（无 keystore 时回落 debug 签名，硬编码 ownbox 默认密码——**品牌相关，不建议移植**）；APK 命名 Ownbox-*。
- `.github/workflows/release.yml` 大改（自动发布）、`preview.yml` 微调。
- `aa60ac5`/`791487d`：CI 用官方 SagerNet/sing-box v1.13.16 源、删除陈旧 go.sum 让 CI 动态生成（T4A 已有自己的处理方式）。

### G. 品牌 / 不建议移植

README、FUNDING、Telegram 链接、Klee/原神图标、`com.ownbox.app` 包名、release.keystore、捐赠码、`AboutFragment` OwnBox 化（-62 行净删 T4A 内容）。

---

## 3. 与 T4A 现状的重叠分析

| OwnBox 改动 | T4A main 现状 | 结论 |
|---|---|---|
| sing-box 1.14 升级 + DNS schema 适配 | 仍为 v1.13.16，旧式 DNS schema | **T4A 迟早要做**，OwnBox 已趟平适配路径（含 fakeip/hosts/reject 迁移细节） |
| 多 UA 订阅回退、parseProxies 重构、b64Decode 健壮化 | T4A 无（`DataStore` 无 `defaultSubscriptionUserAgent`） | 纯增量，可直接移植 |
| Room defaultValue NULL + 删库重建兜底 | T4A 无（同样存在迁移崩溃风险） | 纯增量，低成本高价值 |
| 负载均衡分组（libcore loadbalance + UI） | T4A 无 | 纯增量；libcore 需随 1.14 升级一起做 |
| 每组 URLTest 选项 / TCP Ping | T4A 无（`Constants.kt` 仅预留 `CONCURRENT_DIAL`） | 纯增量 |
| 落地 IP / 流量图表 / IP 纯度 / 媒体解锁 / 国旗 | T4A 无 | 纯增量，但体量大、偏"成品 App"向 |
| activity-alias 图标主题系统 | T4A 已有 `CustomIconFragment`（custom-icon-pack，方案不同） | **功能重叠**，需二选一或融合 |
| urlTest 单次握手重写（libcore/box.go） | T4A main 是"预热+复用双 HEAD"方案 | 两种测速哲学冲突，需决策（OwnBox 称双 HEAD 在部分节点假成功/连接重置） |
| assets.sh 限流修复 | T4A 用原版 API 方式 | 低成本可移植 |
| xhttp `qtls.Dial` 签名修复 | T4A 的 xhttp 移植基于 1.13 API | 随 1.14 升级一并处理 |
| 品牌/签名/包名/README | — | 不移植 |

---

## 4. 可移植性分级建议（供 propose 参考）

**P0（低成本、高确定性、无依赖）**
1. Room `ssrBean/snellBean` defaultValue NULL + 数据库打开失败删库重建兜底（`f231a46`）
2. `b64Decode` 健壮化（`c6142f6`）
3. `parseProxies` scheme 感知切分 + 单链接订阅判定 + 行解析优先（`74e97e7`）
4. V2Ray/Trojan 链接 fragment 剥离与 URL-decode 节点名、`toHttpUrlOrNull` 防崩溃（`74e97e7`/`d1b9b95`）
5. Snell 非标准链接 regex 回退解析（`fa9b523` 系）
6. Hysteria hopPorts 规范化（`fa9b523` 系）
7. YAML/JSON/WG 解析逐条容错（`c6142f6`）
8. assets.sh 限流修复（`4d73b6c`）
9. MaterialCardView 主题崩溃修复（`a5c0685` 中 themes 部分）
10. `2481d78` 类杂项编译/运行时修复

**P1（中等成本、独立成 change）**
11. 订阅多 UA 回退下载 + 订阅 UA 设置 UI（`c6142f6`+`74e97e7`+`SubscriptionUserAgentPreference`）
12. 每组 URLTest 选项 + TCP Ping + 测试 URL 迁移 Cloudflare
13. Protocols 去重键增强
14. WireGuard/AWG URI 解析（`parseWireGuardLink`）

**P2（大工程，建议单独立项）**
15. sing-box 1.14 升级全套（go.mod、libcore API 适配、DNS schema、fakeip、ruleset、xhttp qtls、TLS fragment 重构）——OwnBox 的 `a5c0685`+libcore diff 是现成参考实现
16. 负载均衡分组（依赖 15）
17. 落地 IP / 流量图表 / 网络工具（IP 纯度、媒体解锁）——产品向功能，按 T4A 产品定位取舍

**不建议**：品牌、图标主题系统（与 T4A custom-icon-pack 冲突）、签名/CI 发布 OwnBox 化、urlTest 双 HEAD→单次握手的替换（除非 T4A 也确认双 HEAD 有假成功问题，可作为可选项）。

---

## 5. 风险与注意事项

1. **OwnBox 代码质量参差**：63 个提交中大量是"修自己上一个提交引入的编译错误"（`578cc59`、`a93b3ea`、`90a5b06` 等），移植时应以最终状态 diff 为准，而非逐提交 cherry-pick。
2. **sing-box 1.14 是破坏性升级**：DNS schema、fakeip、ruleset Tag、平台接口、qtls 签名全变；T4A 的 XHTTP 移植层（libcore/protocol/vless/**）需同步适配。
3. **DB 版本**：两边都是 Room version 9，但 schema 细节已分叉（OwnBox 加了 defaultValue NULL），移植 DB 相关改动时需重做 schema 导出与迁移验证。
4. **测试 URL 语义变化**：OwnBox 把 gstatic 204 强制迁移到 Cloudflare（getter 拦截旧值），移植时注意这是行为变更。
5. OwnBox 删除了 T4A 的部分能力（如 `ThroneDesktopBackupImporter`、`SpeedTestRunner`、`SingBoxOutboundParser`、`XhttpExtraConverter` 在其仓库中不存在——是移植时丢弃的），**不能反向假设 OwnBox HEAD 是 T4A 超集**；移植必须按功能点摘取。

---

## 6. 关键提交索引（OwnBox 侧）

| 提交 | 主题 |
|---|---|
| `e541277` | 分叉后首个大提交（OwnBox 化 + 部分前置功能） |
| `b6652ab` | 整体移植 T4A 2026-09-04 状态（基线对齐点） |
| `f231a46` | Room defaultValue NULL 迁移修复 |
| `3b3ce03` | 并发拨号、AMOLED、国旗、剪贴板自动导入、TCP 优化 |
| `fa9b523` | AWG 兼容、URLTest 选项、DNS leak 面板、widget |
| `18f73dc` | 实时流量图表（Clash API）+ widget |
| `35e2a7f` | 落地 IP + 网络工具（IP 纯度/媒体解锁） |
| `5c14552` | 订阅 UA 设置、Snell 协议、XHTTP alias |
| `9160df5` | **sing-box 1.14 升级**、原地测速、连接图标 |
| `a5c0685` | **1.14 DNS schema 适配** + MaterialCardView 崩溃修复 |
| `c6142f6` | 订阅多 UA 回退 + base64 健壮化 |
| `74e97e7` | 节点解析修复 + TLS fragment + **负载均衡** |
| `4d73b6c` | assets.sh 限流修复 |