# Design: port-ownbox-fixes

## Context

动机与范围见 proposal.md；对端改动全景见 `ROO_OWN_RESEARCH.md`。本设计只覆盖"如何安全地把对端与 sing-box v1.13.16 兼容的修复摘取进 T4A"。

关键现状约束：

- T4A `RawUpdater` 订阅下载为单 UA（`subscription.customUserAgent ?: USER_AGENT`）单次尝试；`ktx/Formats.kt` 的 `parseProxies` 按行+空格无脑切分；`Util.b64Decode` 不补 padding；`Protocols` 去重键仅 `serverAddress+serverPort+type`。
- T4A `SagerDatabase` 已是 `version = 9`，但 `app/schemas/.../SagerDatabase/` 只导出到 `8.json`，**缺 9.json 导出**；`ProxyEntity.ssrBean/snellBean` 无 `defaultValue = "NULL"` 声明。
- T4A 备份（`BackupFragment.doBackup`）导出 `PublicDatabase.kvPairDao` 全部键值，新增 `configurationStore` 键自动纳入备份，无需移植对端新建的 `BackupHelper.kt`。
- `libcore/.gitignore` 已忽略 `go.sum` 且仓库未提交该文件——对端"删除陈旧 go.sum"修复无对应对象。
- 本地不编译 Android/Go；Room schema 导出与编译验证依赖 GitHub Actions。

## Goals / Non-Goals

**Goals:**

- 以对端最终状态 diff（`t4a/main..OwnBox-HEAD`）为唯一摘取来源，按功能点移植，不逐提交 cherry-pick。
- 每个移植点在 T4A 代码风格下重写，注释为中性技术描述。
- 保持 `SINGBOX_VERSION=v1.13.16` 不变；所有改动经官方 1.13.16 schema 或现有行为验证兼容。

**Non-Goals:**

- 不升级 sing-box、不改 libcore Go 代码、不做 DNS schema 适配。
- 不移植：负载均衡、每组 URLTest、TCP Ping、落地 IP、流量图表、网络工具、国旗、widget、图标系统、订阅 UA 设置 UI、urlTest 测速哲学替换、品牌/签名/包名。
- 不移植对端 `Theme.kt` 中默认主题改 GREEN、`DynamicColors.applyIfAvailable` 调用及 `Theme.OwnBox.Dialog` 等主题系统改动（仅取 AMOLED overlay）。

## Decisions

1. **移植来源与注释约束**：所有代码以对端 `t4a/main..HEAD` 最终状态 diff 为参考手工摘取并适配 T4A 现状；所有新增/修改注释重写为不含 "OwnBox" 字样的中性描述（如"参考实现来自姊妹分支的修复"一律不写来源，只写技术原因）。任务收尾用 `tools/diagnostics/` 下新增脚本做全仓注释扫描（遵守 uv 工作流）。
   - 备选：逐提交 cherry-pick——否决，对端 63 提交中大量是修复自身编译错误的往返提交，历史不可用。

2. **多 UA 回退链**：候选链 = 订阅自定义 UA（非空时）→ T4A 现行默认 `USER_AGENT` → `clash-meta` → `v2rayN/7.8.2` → `sing-box/1.14.0`。与对端差异：不引入 `Singbox/1.14` 作为第二顺位（对端默认 UA 语义与其设置 UI 绑定，T4A 未批准该 UI），保留 T4A 现有默认 UA 在链中。实现为 `RawUpdater` 内循环下载+解析，成功即 break；`Subscription-Userinfo`/`content-disposition` 取"最后一次非空响应"而非"最后一次尝试"。
   - 备选：引入 DataStore 默认 UA 设置——否决（未批准 UI）。

3. **`parseProxies` 切分策略**：移植对端 scheme 统计方案（18 种 scheme 计数 >1 才按空格切分），并保留 T4A 现有 `sn://` 内部协议分支不动；行解析优先规则按对端（`entitiesByLine.size >= entities.size` 取行解析）。

4. **TLS fragment 发射方式**：设为**条件项**。实施前先联网核对 sing-box 官方 v1.13.16 源码/文档中 TLS options 是否支持 `fragment`/`record_fragment`/`fragment_fallback_delay` 字段（对端提交信息称其为修复，且该字段族自 sing-box 1.12 起进入官方 schema）。核对通过则替换旧式"direct outbound + 路由规则"发射并删除 `TAG_FRAGMENT` 相关路径；不支持则放弃本项并在本文件记录结论，不阻塞其他批次。
   - 备选：保留旧式发射——否决，旧式依赖 direct outbound + 路由规则组合，对端实测存在不生效场景。

5. **数据库稳定性**：
   - `ProxyEntity` 两个可空 Bean 列加 `@ColumnInfo(defaultValue = "NULL")`。
   - `SagerDatabase.instance` 改为"构建 → 试打开 → 失败记日志 → 删库 → 重建"，并加 `fallbackToDestructiveMigrationOnDowngrade()`。
   - schema 导出：修改实体后由 GitHub Actions 构建导出 `9.json` 并回填提交；同时参照对端为 `7.json`/`8.json` 历史导出补齐 defaultValue（仅当与 T4A 实体历史一致时，避免伪造历史 schema——若 T4A 历史实体本就无该列差异则不动）。
   - 风险控制：删库重建仅在接受打开异常时触发；正常路径（含迁移失败）仍走 `fallbackToDestructiveMigration`，不扩大数据丢失面。

6. **AMOLED 开关**：`DataStore.amoledTheme`（键 `amoledTheme`，默认 false）+ `Theme.apply/applyDialog` 在 `usingNightMode()` 时叠加 `Theme.SagerNet.Amoled` overlay（纯黑背景/表面、`cardElevatedSurfaceColor` 近黑）+ `global_preferences.xml` SwitchPreference + `values` 与 `values-zh-rCN/-rHK/-rTW` 等语言 strings。备份无需改动：T4A `doBackup` 导出 `PublicDatabase.kvPairDao` 全量键值，新键自动纳入。不移植对端 `defaultTheme()=GREEN`、`DynamicColors` 调用。
   - 备选：把 overlay 并入各主题 style——否决，overlay 方式零侵入且与现有主题 ID 语义解耦。

7. **主题 attr 兼容修复（评估项）**：先在 T4A 现有 `themes.xml`/布局中确认是否存在 MaterialCardView 在旧主题下解析 `colorSurface`/`textAppearanceBody*` 失败的崩溃路径（对端崩溃由其新增 Material 布局触发，T4A 未必复现）；确认存在才补 attr 映射，否则跳过并在任务中记录结论。

8. **WireGuard/AWG URI 解析**：对端 `WireGuardFmt.kt` 主体与 T4A 一致（diff 仅 +61 行 `parseWireGuardLink`），可直接移植；挂接点按 T4A `parseProxies`/`RawUpdater` 现有 scheme 分发结构接入 `wireguard://`/`awg://`，命名前缀与 AWG 参数识别逻辑照搬。与 `migrate-wireguard-endpoint` 已归档能力（endpoint 生成）不冲突——本项只新增"URI → Bean"入口。

9. **assets.sh 优化**：移植对端 `get_latest_release`（302 Location 解析 → `GITHUB_TOKEN` API → 原始 API）与版本号为空时的 `releases/latest/download` 直链回退；保持 T4A 现有变量名与下游 `xz -9` 流程不变。go.sum 项：任务中仅复核 `libcore/` 无已提交 `go.sum`（已预核实为真），无代码改动。

## Risks / Trade-offs

- [对端代码质量参差，直接照搬可能引入新问题] → 以最终状态 diff 为准、逐点适配 T4A 现状；每个功能点配单元测试或静态校验；分批交 CI。
- [多 UA 回退增加订阅更新耗时（最坏 5 次下载）] → 仅在解析结果为空或下载失败时回退；成功即停；日志记录每次候选结果便于诊断。
- [删库重建兜底会清空配置档] → 仅在数据库打开抛异常（损坏）时触发，且先记录原始错误；正常迁移失败仍走既有破坏性迁移路径，行为面不扩大。
- [Room schema 9.json 缺失与 defaultValue 变更] → 实体注解修改后必须经 CI 构建导出并提交 9.json；本地不手工伪造 schema；若 CI 导出与对端 9.json 不一致以 T4A 实体为准。
- [TLS fragment 新字段在 1.13.16 的支持未定] → 决策 4 的条件门禁；验证失败即放弃，不影响其他批次。
- [AMOLED overlay 与个别自定义背景色页面叠加后对比度异常] → overlay 仅覆盖背景/表面/卡片提升色，不改动强调色；真机验证夜间模式主要页面。
- [对端修复中夹带 T4A 不存在的文件引用（如 MediaUnlock/TrafficChart）] → 摘取时逐文件核对 T4A 是否存在对应物，不存在即跳过（已预核实 `2481d78` 在 T4A 无适用对象）。

## Migration Plan

- 无破坏性发布动作：所有改动随常规版本构建发布。
- 数据库：defaultValue 声明 + 试打开兜底对老用户透明；极端损坏场景从"崩溃循环"变为"重建空库"，属可接受降级。
- 回滚：各批次独立提交，可按批 revert；assets.sh 改动不影响产物布局，可独立回滚。

## Open Questions

- 无阻塞项。TLS fragment 字段兼容性验证（决策 4）按计划在实施首步联网完成，结论不改变 specs 与任务拆分（仅决定该单项做或不做）。