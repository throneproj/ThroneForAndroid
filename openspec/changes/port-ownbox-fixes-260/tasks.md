# Tasks: port-ownbox-fixes-260

> 滚动验证约定：每个"实现批次"完成后立即执行其后的"验证"小节，收到 CI/真机结果前不开始下一批次。所有新增/修改代码注释不得出现 "OwnBox" 字样（由既有 `tools/diagnostics/check_no_brand_comments.py` 把关）。

## 1. 前置核查（外部 API 复核）

- [ ] 1.1 联网核对 sing-box 官方 v1.13.16（tag v1.13.16，github.com/SagerNet/sing-box）：Hysteria 与 TUIC outbound options 是否含 `udp_fragment` 字段；DNS rule 是否支持 `query_type` + `action: reject`；route rule 是否支持 `ip_version` + `action: reject`。结论与依据回写 design.md 决策 6（全部支持→批次三/五按计划实施；个别不支持→对应单项记录放弃）
- [ ] 1.2 复核 Android `VpnService.Builder.addAddress/addRoute` 官方文档对 IPv6 路由的行为约束，结论回写 design.md 决策 3

## 2. 批次一：数据库备份兜底 + UI 防崩溃杂项

- [ ] 2.1 `SagerDatabase`：试打开失败路径中，删库前将原库文件复制为 `<db>.bak_<timestamp>`（同目录）；备份失败仅记日志、不阻断重建
- [ ] 2.2 `ktx/Utils.kt`：`Fragment.snackbar` 三级回退（activity → `MessageStore.getCurrentActivity()` → decorView）+ 新增 `safeSnackbar`（Toast 兜底）；`getColorAttr` try-catch + attribute 缺失返回透明色
- [ ] 2.3 `BackupFragment`/`GroupFragment` 导出路径改用安全 resolver（context → MessageStore → application）；`ToolbarFragment` toolbar 判空
- [ ] 2.4 提交批次一并推送，触发 GitHub Actions 构建与单元测试；回传证据：workflow 编译成功 + 单测通过（适用 workflow：仓库现有 Android CI build/test job；本地不编译）

## 3. 批次二：IPv6 泄露修复全套

- [ ] 3.1 `bg/VpnService.kt`：IPv6Mode.DISABLE 时仍始终添加 IPv6 虚拟地址（/126）与路由（`2000::/3` 或 `::/0`，另加 `fc00::/7`）
- [ ] 3.2 `fmt/ConfigBuilder.kt`（ipv6Mode == DISABLE）：`dns.strategy = "ipv4_only"`、`autoDnsDomainStrategy` 强制 `ipv4_only`、链路 `defaultServerDomainStrategy` 强制 `ipv4_only`；DNS 规则头部插入 AAAA reject；route 规则头部插入 `ip_version=6 reject`；fakeip 去 `inet6_range` 且 query_type 仅 `A`；tun `address` 不因 DISABLE 省略 v6 虚拟地址
- [ ] 3.3 提交批次二并推送，触发 CI 编译；真机场景（可后置到 8.2）：禁用 IPv6 模式下连接节点、访问网页、执行 URL 测试，预期正常且无 v6 泄露（回传连接与访问截图）

## 4. 批次三：批量订阅更新并发化 + RawUpdater 健壮化

- [ ] 4.1 `group/GroupUpdater.kt`：`executeUpdate` 改 `supervisorScope`；`subscription` 判空；后台失败静默（仅 `byUser` 报错）；重复触发返回 false
- [ ] 4.2 `ui/GroupFragment.kt`：批量更新改 `supervisorScope + async/awaitAll` 并发 + 逐个 try-catch + 成功/失败汇总（经 safeSnackbar）
- [ ] 4.3 `group/RawUpdater.kt`：diff 熔断器（exists >= 10 且 fetched < exists*70% 时跳过删除）；顺序匹配保持 userOrder 稳定；toInsert 先收集后入库；代理拉取失败回退直连重试；捕获 `Profile-Title`/`X-Profile-Title` 头（含 base64: 解码）
- [ ] 4.4 提交批次三并推送，触发 CI；回传证据：编译成功；真机场景（可后置到 8.2）：多订阅批量更新（含一个坏订阅），预期其余订阅正常更新且显示汇总提示

## 5. 批次四：剪贴板去重修正 + 去重键重构

- [ ] 5.1 `moe/matsuri/nb4a/Protocols.kt`：`Deduplication` 键重构为按协议字段（uuid/password/sni/pubKey/privateKey/peerPublicKey/localAddress/credentials 等）+ `finalPort`（Hysteria 取 `getFirstPort`），name 不参与哈希
- [ ] 5.2 `ktx/Formats.kt`：新增 `dedupKey()`/`deduplicateProxies()` 扩展函数（复用 `Protocols.Deduplication`）；`ui/MainActivity.kt` 剪贴板导入仅当目标/当前分组 `subscription.deduplication == true` 时去重
- [ ] 5.3 新增/扩展 JVM 单元测试：去重三场景（同服务器不同凭据保留、同名不同凭据保留、完全相同合并）
- [ ] 5.4 提交批次四并推送，触发 CI；回传证据：编译成功 + 新增单测通过

## 6. 批次五：协议连通性兼容修复

- [ ] 6.1 `fmt/hysteria/HysteriaFmt.kt`：`getFirstPort` 兼容 `-` 分隔；SNI 空回退 serverAddress；h2 alpn 从 bean 解析；`udp_fragment = true`（依据 1.1 结论；不移植 `start:end` 输出格式）
- [ ] 6.2 `fmt/tuic/TuicFmt.kt`：SNI 空回退 + 尊重 `disableSNI`；`udp_fragment = true`
- [ ] 6.3 `fmt/shadowsocks/ShadowsocksFmt.kt`：v2ray-plugin 补 `mux=0`；空 plugin_opts 置 null
- [ ] 6.4 `fmt/v2ray/V2RayFmt.kt`：query 特殊字符 percent-encode 重试 + URI 回退；`net=` 参数识别；kcp headerType 非法回退 none；xhttp extra 转换失败回退原文
- [ ] 6.5 扩展 `HysteriaFmtTest`（getFirstPort 兼容）与 V2Ray 解析测试（特殊字符 query、非法 headerType）
- [ ] 6.6 提交批次四并推送，触发 CI；回传证据：编译成功 + 单测通过；真机场景（可后置到 8.2）：导入 SNI 为空的 hy2 节点、v2ray-plugin SS 节点、带特殊字符 query 的 vless 链接各一条，节点正常生成并可连接

## 7. 批次六：纯白模式主题

- [ ] 7.1 `utils/Theme.kt`：新增 `WHITE` 主题常量与 `isWhiteTheme()`/`getPrimaryColor()`；沿用现有 `appTheme` 持久化键（新 ID 入枚举即可）
- [ ] 7.2 `values/themes.xml` 新增纯白主题 style；`ColorPickerPreference` 预设色板加入纯白项；`values/strings.xml` 与 `values-zh-rCN/-rHK/-rTW` 等语言目录补充可翻译文案
- [ ] 7.3 `ThemedActivity`（系统栏图标深色）、`ToolbarFragment`（工具栏白底深字深图标）、`MainActivity`（FAB 深色底浅色图标）适配；主题变更生效沿用现有 `needRestart()` 机制
- [ ] 7.4 提交批次五并推送，触发 CI；真机场景（可后置到 8.2）：非夜间模式选择纯白主题，重启后工具栏/FAB/系统栏呈纯白外观且图标可读；夜间模式下不出现纯白底色

## 8. 收尾与规范同步

- [ ] 8.1 运行 `uv run tools/diagnostics/check_no_brand_comments.py` 与 `uv run tools/diagnostics/roo_check_repo_governance.py`，均退出码 0
- [ ] 8.2 `openspec validate --change port-ownbox-fixes-260` 通过；汇总并执行后置真机清单（3.3、4.4、6.6、7.4），全部通过后变更进入待归档状态
