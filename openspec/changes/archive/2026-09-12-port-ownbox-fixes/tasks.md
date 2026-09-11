# Tasks: port-ownbox-fixes

> 滚动验证约定：每个"实现批次"完成后立即执行其后的"验证"小节，收到 CI/真机结果前不开始下一批次。所有新增/修改代码注释不得出现 "OwnBox" 字样（由 1.3 的扫描脚本把关）。

## 1. 前置核查与工具准备

- [x] 1.1 联网核对 sing-box 官方 v1.13.16（tag v1.13.16，github.com/SagerNet/sing-box）TLS options 是否支持 `fragment`/`record_fragment`/`fragment_fallback_delay` 字段，把结论与依据回写 design.md 决策 4（支持→批次四实施；不支持→记录放弃）
- [x] 1.2 复核 `libcore/` 目录无已提交 `go.sum`（`libcore/.gitignore` 已忽略；预期结论：对端 go.sum 修复无对应对象，仅记录，不改代码）
- [x] 1.2.5 完善 action 加速：三个含 libcore 构建的 workflow（ci/preview/release）在 `Install Golang` 之后、`Native Build` 之前新增 `actions/cache` 缓存 Go module 下载目录（`~/go/pkg/mod`，key 基于 `hashFiles('libcore/go.mod')` + runner OS），使 LibCore AAR 缓存未命中时不再全量重新下载 Go 依赖；提交并推送触发 CI，回传证据：缓存未命中路径下构建成功且 Go 模块缓存生效（restore 日志）
- [x] 1.3 新增 `tools/diagnostics/check_no_brand_comments.py`（uv 工作流）：扫描 `app/`、`libcore/`、`buildScript/`、`buildSrc/` 源码注释与字符串字面量中不含 "OwnBox" 字样，语法合法；`uv run tools/diagnostics/check_no_brand_comments.py` 退出码 0

## 2. 批次一：订阅下载与文本解析健壮化

- [x] 2.1 `RawUpdater`：实现候选 UA 链回退下载（自定义 UA → 现行默认 UA → `clash-meta` → `v2rayN/7.8.2` → `sing-box/1.14.0`），成功即停；跨候选保留最后一次非空 `Subscription-Userinfo` 与 `content-disposition`；移除 H3 直连尝试；注释为中性描述
- [x] 2.2 `ktx/Formats.kt` `parseProxies`：scheme 感知切分（18 种协议 scheme 计数）、单链接才触发订阅跳转语义、行解析结果优先；保留 T4A 现有 `sn://` 分支
- [x] 2.3 `moe/matsuri/nb4a/utils/Util.b64Decode`：trim/去空白/补 padding/`URL_SAFE` flag/cleaned+padded 双轮尝试
- [x] 2.4 `RawUpdater`：Clash YAML proxies 逐条 try-catch 且 type 小写化；JSON 与 WireGuard 分支空结果继续后续分支
- [x] 2.5 `moe.matsuri/nb4a/Protocols.kt`：去重键纳入 uuid/password/path/sni/realityPubKey/name 等协议适用特征
- [x] 2.6 新增/扩展 JVM 单元测试覆盖 2.2/2.3/2.5（多链接单行、混合订阅链接文本、坏 padding base64、同服务器不同凭据去重）
- [x] 2.7 提交批次一并推送，触发 GitHub Actions 构建与单元测试；回传证据：workflow 编译成功 + 单测通过日志（适用 workflow：仓库现有 Android CI build/test job；本地不编译，静态校验仅限 1.3 脚本与 `openspec validate`）

## 3. 批次二：协议链接解析修复

- [x] 3.1 `V2RayFmt.kt`/`TrojanFmt.kt`：fragment 剥离 + URL-decode 节点名、`toHttpUrlOrNull` + 可读错误、Kitsunebi 分支同步修复
- [x] 3.2 `SnellFmt.kt`：标准解析失败时 regex 回退（psk@host:port、query 别名、fragment 节点名）；移植对端 `SnellFmtTest.kt` 用例并适配 T4A Bean
- [x] 3.3 `HysteriaFmt.kt` `hopPortsToSingboxList`：区间分隔符规范化、trim、数字校验、非法片段丢弃
- [x] 3.4 `WireGuardFmt.kt`：新增 `wireguard://`/`awg://` URI 解析（base64 整配置回退、AWG 参数识别、兼容前缀命名），并按 T4A `parseProxies`/`RawUpdater` scheme 分发结构挂接
- [x] 3.5 提交批次二并推送，触发 CI；回传证据：编译成功 + 新增单测通过；真机场景（可后置到 8.2）：导入带中文 fragment 的 vless 链接、非标准 snell 链接、awg:// 链接各一条，节点正常生成并可连接

## 4. 批次三：TLS fragment 发射方式（条件项）

- [x] 4.1 依据 1.1 结论执行：若官方 1.13.16 支持新字段，`ConfigBuilder` 将 fragment 发射改为代理 outbound TLS 内联 `fragment`/`record_fragment`/`fragment_fallback_delay`（delay 取 `fragmentInterval` 区间首值），删除独立 fragment outbound 与相关路由规则路径；若不支持，在本任务勾选时注明"已放弃"并跳过 4.2 的真机项
  - 实施记录：1.1 结论为"支持"，已实施。`ConfigBuilder.kt` 在 needGlobal 且启用 TLS 分片时代理 outbound TLS 启用时，经 `_hack_config_map["tls"]` 深合并内联 `fragment=true`/`record_fragment=true`/`fragment_fallback_delay`（取 `fragmentInterval` 区间首值，纯数字补 `ms` 单位——sing-box `badoption.Duration` 要求带单位时长）；删除 `TAG_FRAGMENT` 常量、独立 fragment direct outbound 及 external mapping 入站的 fragment 路由规则。联网复核补充依据：sing-box v1.13.16 `option/direct.go` 已无 fragment 字段且使用 `UnmarshalDisallowUnknownFields`，旧式发射在该版本会直接导致配置解析失败。
- [x] 4.2 提交批次三并推送，触发 CI 编译；真机场景：启用 TLS 分片访问被 SNI 阻断的站点，预期连接成功且生成配置中含内联 fragment 字段（回传生成配置片段与连接结果；条件项不适用时注明原因）
  - 验证记录：CI 编译通过；真机场景（TLS 分片连接验证 + 生成配置片段）后置到 8.3 汇总。

## 5. 批次四：配置档数据库稳定性

- [x] 5.1 `ProxyEntity`：`ssrBean`/`snellBean` 列加 `@ColumnInfo(defaultValue = "NULL")`
- [x] 5.2 `SagerDatabase.instance`：构建 → 试打开 → 失败记日志 → 删库 → 重建；加 `fallbackToDestructiveMigrationOnDowngrade()`
- [x] 5.3 通过 GitHub Actions 构建导出 Room schema `9.json` 并回填提交；核对 `7.json`/`8.json` 历史导出是否需补 defaultValue（仅当与 T4A 实体历史一致，否则记录不动）
- [x] 5.4 提交批次四并推送，触发 CI；回传证据：编译成功、schema 导出文件入库；真机场景：老版本升级安装后正常启动、配置档完整（回传升级前后节点列表截图）

## 6. 批次五：AMOLED 纯黑模式开关

- [x] 6.1 `DataStore.amoledTheme`（键 `amoledTheme`，默认 false）+ `Theme.apply/applyDialog` 夜间模式下叠加 `Theme.SagerNet.Amoled` overlay；不改动默认主题、动态主题与既有主题 ID
- [x] 6.2 `themes.xml` 新增 `Theme.SagerNet.Amoled` overlay style；`global_preferences.xml` 增加 SwitchPreference；`values/strings.xml` 与 `values-zh-rCN/-rHK/-rTW`（及其他现有语言目录按需）补充可翻译文案
- [x] 6.3 执行设计决策 7 的评估：确认 T4A 是否存在 MaterialCardView 主题 attr 解析崩溃路径；存在则补 `themes.xml` attr 映射，不存在则记录"跳过"结论
  - 评估结论：跳过。T4A 所有承载布局的 Activity/Dialog 均经 `ThemedActivity` 应用继承 `Theme.MaterialComponents.DayNight` 的 `Theme.SagerNet*` 主题（manifest 中 `Theme.SagerNet`/`Theme.SagerNet.Dialog`/`Theme.Start` 皆继承 MaterialComponents，`colorSurface`/`textAppearanceBody*` 等 attr 由父主题完整解析）；仅有的三个 `Theme.Translucent.NoTitleBar` Activity（QuickToggle/QuickEnable/QuickDisable 快捷方式）不加载含 MaterialCardView 的布局。不存在对端同型崩溃路径，无需补 attr 映射。
- [x] 6.4 提交批次五并推送，触发 CI；真机场景：夜间模式开/关 AMOLED 开关各截一张主要页面（主列表、设置、Dialog），预期纯黑生效/恢复原样，备份导出 JSON 中含 `amoledTheme` 键

## 7. 批次六：geoip/geosite 版本获取优化

- [x] 7.1 `buildScript/lib/assets.sh`：`get_latest_release` 改为 302 Location 解析优先 → `GITHUB_TOKEN` API → 原始 API；版本号为空时回退 `releases/latest/download` 直链；保持下游 `xz -9` 与变量布局不变
- [x] 7.2 提交批次六并推送，触发含 assets 步骤的 workflow（preview/release）；回传证据：assets 步骤日志显示版本获取成功且无 API 限流错误（本地无法验证网络行为，注明原因）

## 8. 收尾与规范同步

- [x] 8.1 运行 `uv run tools/diagnostics/check_no_brand_comments.py` 与 `uv run tools/diagnostics/roo_check_repo_governance.py`，均退出码 0
- [x] 8.2 `openspec validate --change port-ownbox-fixes` 通过；确认 delta specs 与实现一致（订阅回退链、AMOLED 行为、数据库韧性）
- [x] 8.3 汇总真机验证清单与结果（批次二 3.5、批次三 4.2、批次四 5.4、批次五 6.4），全部通过后变更进入待归档状态