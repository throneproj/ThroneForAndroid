# Proposal: port-ownbox-fixes

## Why

姊妹分支 OwnBoxForAndroid（自 T4A v1.4.2 分叉后整体移植过 T4A 2026-09-04 状态）在其自有迭代中积累了一批与当前 `SINGBOX_VERSION=v1.13.16` 兼容的订阅解析、协议链接解析、稳定性与构建链修复（详见 `ROO_OWN_RESEARCH.md`）。T4A 当前代码仍存在同型缺陷：订阅下载单 UA 失败即整体失败、`parseProxies` 无脑按空格切分、Base64 解码不补 padding、畸形 vmess/vless/trojan 链接直接抛异常、Room 新增可空 Bean 列缺 defaultValue 的迁移崩溃风险、geoip/geosite 版本查询依赖 GitHub API 易被限流。按用户批准范围（调研报告 C、D 两类 + E 中仅 AMOLED 开关 + F 中 302 优化与 go.sum 项 + 各协议兼容性修复）将这些修复移植回 T4A。

## What Changes

- **订阅下载多 UA 回退**（`RawUpdater`）：单一 UA 失败或解析出 0 节点时，依次尝试候选 UA（用户自定义 UA → `clash-meta` → `v2rayN/7.8.2` → `Throne/1.0.0` → `sing-box/1.14.0`），直到解析出非空节点；跨候选保留最后一次成功的 `Subscription-Userinfo` 与 `content-disposition`；下载客户端移除 H3 直连尝试。不引入订阅 UA 设置 UI（未批准）。
- **`parseProxies` 重构**（`ktx/Formats.kt`）：按 18 种协议 scheme 感知切分同一行多个链接；仅当整段文本是单条 `clash://`/`sn://subscription` 链接时抛 `SubscriptionFoundException`；行解析与逐链接解析结果取更优者（行解析优先）。
- **Base64 解码健壮化**（`moe.matsuri.nb4a.utils.Util.b64Decode`）：trim、去空白/换行、自动补 padding、增加 `URL_SAFE` flag、cleaned/padded 双轮尝试。
- **订阅内容解析逐条容错**（`RawUpdater`）：Clash YAML proxies 逐条 try-catch 且 type 小写化，单条坏节点不毁掉整个订阅；JSON、WireGuard `[Interface]` 分支解析结果为空时继续尝试后续分支而非提前返回。
- **节点去重键增强**（`moe.matsuri.nb4a.Protocols`）：dedup key 从 `serverAddress+serverPort+type` 扩展为包含 uuid/password/path/sni/realityPubKey/name 等凭据特征，避免同服务器不同凭据节点被错误合并。
- **协议链接解析修复**（均兼容 sing-box 1.13.16，不涉及内核升级）：
  - vmess/vless/trojan：先剥离 `#fragment` 并 URL-decode 作为节点名，再解析 URL；`toHttpUrl()` 改为 `toHttpUrlOrNull()` + 显式错误，畸形链接不再崩溃；Kitsunebi 分支同步修复。
  - snell：非标准 `snell://` 链接的 regex 回退解析（psk@host:port、query 参数多别名、fragment 节点名）。
  - hysteria：`hopPortsToSingboxList` 端口区间规范化（`:`→`-`、trim、数字校验），不再生成非法端口列表。
  - wireguard/awg：新增 `wireguard://`/`awg://` URI 解析（含 base64 整配置回退、AWG 混淆参数 Jc/S1/H1 识别与 `[AWG-Compat]` 前缀），与现有 WireGuard endpoint 生成逻辑对接。
- **TLS fragment 发射方式修正**（`fmt/ConfigBuilder.kt`，待设计阶段按 sing-box 1.13.16 官方 schema 验证后实施）：将"独立 direct outbound + 路由规则"的旧式 fragment 发射改为代理 outbound TLS 选项内联 `fragment`/`record_fragment`/`fragment_fallback_delay`；若验证发现 1.13.16 不支持新字段则放弃本项并记录。
- **数据库稳定性**（`SagerDatabase`/`ProxyEntity`）：`ssrBean`/`snellBean` 列声明 `@ColumnInfo(defaultValue = "NULL")` 消除迁移崩溃；数据库初始化改为"先试打开 → 失败删库重建"兜底并增加 `fallbackToDestructiveMigrationOnDowngrade()`。
- **主题 attr 兼容修复**（评估项）：`themes.xml` 补齐 `colorSurface`/`textAppearanceBody*` 等 Material attr 映射以消除 MaterialCardView 在旧主题下的解析崩溃——仅当确认 T4A 存在同型崩溃路径时移植。
- **AMOLED 纯黑模式开关**（仅此一项，不移植其他主题系统改动）：`DataStore.amoledTheme` 布尔设置 + `Theme.apply/applyDialog` 在夜间模式下叠加 `Theme.SagerNet.Amoled` overlay style（纯黑背景/表面色）+ 全局设置 SwitchPreference + 多语言字符串；该设置纳入备份。不移植默认主题改为 GREEN、DynamicColors 调用等其余主题改动。
- **构建脚本 geoip/geosite 版本获取优化**（`buildScript/lib/assets.sh`）：`get_latest_release` 优先解析 `releases/latest` 302 重定向 Location（绕过 GitHub API 限流），失败回退 `GITHUB_TOKEN` 认证 API，再回退 `releases/latest/download` 直链；版本号为空时使用直链兜底。
- **go.sum 项核实结论**：T4A `libcore/.gitignore` 已忽略 `go.sum` 且仓库未提交该文件，对端"删除陈旧 go.sum"修复无对应对象，本变更不引入代码改动，仅在任务中做一次存在性复核。
- **约束**：所有新增/修改代码的注释不得出现 "OwnBox" 字样；移植时以 OwnBox 最终状态 diff 为准摘取，不逐提交 cherry-pick。
- **明确排除**（未批准或需 sing-box 1.14 升级）：sing-box 1.14 升级及全套 DNS schema 适配、libcore `loadbalance` outbound、每组 URLTest 选项、TCP Ping、落地 IP、流量图表、IP 纯度/媒体解锁工具、国旗、桌面 widget、图标主题系统、订阅 UA 设置 UI、urlTest 双 HEAD→单次握手替换、品牌/签名/包名改动。

## Capabilities

### New Capabilities

（无——本变更不引入新能力域。）

### Modified Capabilities

- `android-application`: 订阅更新 MUST 在单一 UA 失败时按候选链回退并保留流量信息/远端名称；订阅文本解析 MUST 按 scheme 感知切分、逐条容错、行解析优先；vmess/vless/trojan/snell/hysteria/wireguard 链接解析 MUST 容忍 fragment、非标准格式与畸形输入而不崩溃；节点去重 MUST 区分同服务器不同凭据；配置档数据库 MUST 在打开失败时安全重建且迁移声明 MUST 与 schema 一致。
- `theme-system`: 新增 AMOLED 纯黑模式开关——启用且处于夜间模式时，应用与 Dialog 主题 MUST 叠加纯黑 overlay style；开关 MUST 持久化并纳入备份。

## Impact

- **Android 应用层**：`app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt`、`ktx/Formats.kt`、`moe/matsuri/nb4a/utils/Util.kt`、`moe/matsuri/nb4a/Protocols.kt`、`fmt/v2ray/V2RayFmt.kt`、`fmt/trojan/TrojanFmt.kt`、`fmt/snell/SnellFmt.kt`、`fmt/hysteria/HysteriaFmt.kt`、`fmt/wireguard/WireGuardFmt.kt`、`database/SagerDatabase.kt`、`database/ProxyEntity.kt`、`utils/Theme.kt`、`database/DataStore.kt`、`utils/BackupHelper.kt`、Room schema 导出（`app/src/main/res/schemas` 或项目现有 schema 目录）。
- **资源**：`values/themes.xml`（AMOLED overlay + 评估性 attr 兼容）、`xml/global_preferences.xml`、`values/strings.xml` 及 `values-zh-rCN/-rHK/-rTW` 等语言目录的 AMOLED 文案。
- **构建链**：`buildScript/lib/assets.sh`（仅版本获取逻辑，不改变产物布局）。
- **不涉及**：libcore Go 代码、`nb4a.properties` 的 `SINGBOX_VERSION`（保持 v1.13.16）、GitHub workflows 布局。
- **外部依赖参考**：sing-box 官方 v1.13.16 schema（https://github.com/sagerNet/sing-box ，tag v1.13.16）用于验证 TLS fragment 新字段兼容性；GitHub releases 302 重定向行为用于 assets.sh 优化。
- **验证方式**：本地仅静态校验与单元测试（`SnellFmtTest` 类比新增解析测试）；编译与真机验证交由 GitHub Actions 与用户真机。