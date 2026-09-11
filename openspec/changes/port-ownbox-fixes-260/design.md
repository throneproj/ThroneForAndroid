# Design: port-ownbox-fixes-260

## Context

动机与范围见 proposal.md；对端 v2.4.0→v2.6.0 增量全景见 `ROO_OWN_RESEARCH.md` 第 0 章。本设计只覆盖"如何安全地把对端与 sing-box v1.13.16 兼容的修复摘取进 T4A"。

关键现状约束：

- T4A 已完成上一轮 `port-ownbox-fixes`（多 UA 回退、parseProxies scheme 切分、b64Decode、Room defaultValue NULL + 试打开删库兜底、AMOLED 开关、assets.sh 302 优化均已落地），本变更在其基础上做增量。
- T4A `SagerDatabase` 为 `version = 9`，`app/schemas/.../SagerDatabase/9.json` 已导出；对端已到 v10（balancerBean 列）——本变更不引入该列，DB 版本保持 9。
- T4A URL 测速链路（`libcore/box.go` 双 HEAD 预热 + `bg/proto/UrlTest.kt` + `SpeedTestRunner`）按用户决策**维持现状**（含 200 状态码判定），本变更不触碰。
- T4A `Protocols.Deduplication` 已含 uuid/password/path/sni/realityPubKey/name 等字段（上一轮移植），本轮按对端 v2.6.0 进一步把 name 移出哈希并补齐 WireGuard/SSH/HTTP/SOCKS 字段与端口语义。
- 本地不编译 Android/Go；编译与真机验证依赖 GitHub Actions。

## Goals / Non-Goals

**Goals:**

- 以对端最终状态 diff（`3769c07..4372435`）为唯一摘取来源，按功能点移植，不逐提交 cherry-pick。
- 每个移植点在 T4A 代码风格下重写，注释为中性技术描述（不含 "OwnBox" 字样，沿用 `check_no_brand_comments.py` 把关）。
- 保持 `SINGBOX_VERSION=v1.13.16` 不变；所有协议字段改动经官方 1.13.16 schema 验证兼容。

**Non-Goals:**

- 不升级 sing-box、不改 libcore Go 代码、不做 DNS schema 适配。
- 不动 URL 测速链路与 TCP Ping：不移植 fallback URL 互备、`>=400` 判定、`urlTestCustomUrl` AIDL、两阶段 Keep-Alive 重写、TcpPing UDP-only 回退与 DNS 预解析；T4A 维持双 HEAD 预热与 200 判定。
- 不移植：BalancerBean 独立负载均衡节点（含 Room v10）、机场名自动提取、订阅资产信息卡片、每组 URLTest URL、lockUserAgent、分组名作为 outbound tag、ConnectivityTestActivity、多规格 Widget、CUSTOM 自定义色值、设置页手风琴、媒体解锁重构、START_STICKY/亮屏 wake/WifiLock、品牌/更名/图标精简、versionCode metadata 动态读取。
- 不移植对端 `hopPortsToSingboxList` 输出格式改为 `start:end` 的变更（sing-box 1.14 语义；T4A 1.13.16 维持 `start-end`）。

## Decisions

1. **移植来源与注释约束**：沿用上一轮约定——以对端最终状态 diff 为参考手工摘取并适配 T4A 现状；注释重写为不含 "OwnBox" 字样的中性描述；收尾运行既有 `tools/diagnostics/check_no_brand_comments.py`。

2. **数据库备份兜底**：在 T4A 现有"试打开 → 失败删库重建"路径中，于删库前将原库文件复制为 `<db>.bak_<System.currentTimeMillis()>`（同目录）；备份失败仅记日志、不阻断重建。不引入对端 Room v10 迁移。
   - 备选：引入 Room v10 + balancerBean——否决（负载均衡不在批准范围）。

3. **IPv6 泄露修复**：完整移植对端方案（VpnService 常驻 v6 地址/路由 + ConfigBuilder AAAA reject/ipv4_only/fakeip 适配）。该方案不依赖 1.14 特有字段：`dns.strategy`、`query_type`、`ip_version`、`action: reject` 均为 1.13.16 官方 schema 字段（实施前按决策 6 联网复核一次）。注意 T4A 与对端 tun `address` 生成结构一致（合并 `address` 字段），可直接对齐。
   - 备选：仅加 route reject 不加 v6 虚拟地址——否决，对端实测存在系统旁路物理网卡路径，必须让系统完整接管。

4. **协议修复的取舍门禁**：逐项核对 sing-box v1.13.16 官方 schema 后实施：
   - **纳入**：Hysteria `getFirstPort` 兼容 `-` 分隔、SNI 空回退 serverAddress、h2 alpn 从 bean 解析、`udp_fragment`（1.13.16 Hysteria/TUIC outbound 均有该字段，实施前联网复核）；TUIC SNI 回退 + `disableSNI` 尊重；SS v2ray-plugin `mux=0`；V2Ray query sanitize 双重回退、`net=` 参数、kcp headerType 回退 none、xhttp extra 转换失败回退原文。
   - **排除**：`hopPortsToSingboxList` 输出 `start:end`（1.14 语义）；Trojan 强制 TLS 与 `server_name` 空回退（对端动机与其 1.14 行为耦合，且 T4A 上一轮已验证 1.13.16 下 Trojan security=tls 语义正常，不引入行为变更）。

5. **批量更新并发化**：`GroupUpdater.executeUpdate` 改 `supervisorScope` + 判空 + 静默后台失败；`GroupFragment` 批量入口改 `supervisorScope + async/awaitAll` 并发 + 逐个 try-catch + 汇总提示（经 `safeSnackbar`）。并发度沿用对端（一次性全部 async，订阅数量级通常 < 20，无需限流）。
   - 备选：信号量限流——否决，增加复杂度且对端实测无并发压力问题。

6. **外部 API 复核清单**（实施首步联网完成，结论回写本文件）：
   - sing-box v1.13.16 `option/hysteria.go`/`option/tuic.go` 是否含 `udp_fragment` 字段；
   - sing-box v1.13.16 DNS rule `query_type` + `action: reject`、route rule `ip_version` + `action: reject` 字段支持；
   - Android `VpnService.Builder.addAddress/addRoute` 对 IPv6 的行为（官方文档）。

7. **剪贴板去重**：`MainActivity` 导入路径读取目标分组（`selectedGroupForImport()`）与当前分组的 `subscription.deduplication`，任一为 true 才 `deduplicateProxies()`；`dedupKey()`/`deduplicateProxies()` 作为 `Formats.kt` 扩展函数实现（内部复用 `Protocols.Deduplication`）。`Protocols.Deduplication` 重构为按协议字段 + `finalPort`（Hysteria 取 `getFirstPort`），name 不再参与。

8. **UI 防崩溃**：`Fragment.snackbar` 重写为三级回退（activity → `MessageStore.getCurrentActivity()` → decorView）+ `safeSnackbar`（Toast 兜底）；`getColorAttr` try-catch + attribute 缺失返回透明色；`BackupFragment`/`GroupFragment` 导出 resolver 改 `(context ?: MessageStore.getCurrentActivity() ?: SagerNet.application)`；`ToolbarFragment` toolbar 判空。不移植对端 `MessageStore` 新增逻辑（T4A 已有该类，仅复用）。

9. **纯白模式（原样移植）**：`Theme` 新增 `WHITE` 常量与 `isWhiteTheme()`/`getPrimaryColor()`；`ColorPickerPreference` 预设色板加入纯白项；`themes.xml` 新增纯白主题 style；`ThemedActivity`（系统栏图标深色）、`ToolbarFragment`（工具栏白底深字深图标）、`MainActivity`（FAB 深色底浅色图标）适配。**不移植**对端 `CUSTOM` 主题、`getClosestThemeForColor()`、`ColorPickerPreference` HSV/RGB 滑条重写、`DataStore.customThemeColor`——仅取"预设菜单多一个纯白选项"这一行为。主题变更生效方式沿用 T4A 现有 `needRestart()` 机制（不移植对端 `ActivityCompat.recreate` 路径）。

## Risks / Trade-offs

- [对端代码质量参差，直接照搬可能引入新问题] → 以最终状态 diff 为准、逐点适配 T4A 现状；每个功能点配单元测试或静态校验；分批交 CI。
- [IPv6 常驻路由改变现有 DISABLE 行为] → 真机验证禁用 IPv6 场景下连接、测速与 DNS 查询（AAAA 应被 reject）；若个别内核版本对 `fc00::/7` 路由异常，回退为仅 `2000::/3`/`::/0` 并记录。
- [supervisorScope 改动影响既有单订阅更新路径] → 单订阅更新走同一 `executeUpdate`，supervisorScope 语义对单任务等价；回归验证单订阅手动更新。
- [去重键重构改变既有去重行为] → 单元测试覆盖"同服务器不同凭据保留、同名不同凭据保留、完全相同合并"三场景；订阅更新 diff 依赖 displayName 匹配，不受 dedup key 影响。
- [纯白模式与个别页面自定义背景叠加后对比度异常] → 纯白适配仅覆盖工具栏/FAB/系统栏图标，不改内容区背景；真机验证主要页面。
- [对端修复中夹带 T4A 不存在的文件引用（如 ConnectivityTestActivity、OwnBoxWidgets）] → 摘取时逐文件核对 T4A 是否存在对应物，不存在即跳过。

## Migration Plan

- 无破坏性发布动作：所有改动随常规版本构建发布。
- 数据库：备份兜底对老用户透明；DB 版本保持 9，无迁移。
- 回滚：各批次独立提交，可按批 revert。

## Open Questions

- 无阻塞项。决策 6 的字段兼容性复核在实施首步联网完成，结论仅决定对应单项做或不做，不改变 specs 与任务拆分。
