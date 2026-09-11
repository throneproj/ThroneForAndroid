## ADDED Requirements

### Requirement: 配置档数据库打开失败时先备份再重建

配置档数据库初始化 MUST 先尝试正常打开；打开失败时 MUST 记录原始错误、将损坏的数据库文件复制为带时间戳的备份文件（存放在原数据库同目录）、删除原文件并重建空数据库，使应用可以继续启动而不是陷入崩溃循环。备份动作失败 MUST NOT 阻断删库重建流程。数据库降级打开 MUST 配置破坏性降级兜底。可空协议 Bean 列的 Room 实体声明 MUST 与导出 schema 的列默认值一致（新增可空列 MUST 声明 NULL 默认值），避免升级迁移因 schema 不一致而崩溃。

#### Scenario: 数据库文件损坏后启动

- **GIVEN** 设备上配置档数据库文件损坏导致打开抛出异常
- **WHEN** 用户启动应用
- **THEN** 应用记录原始错误后将原数据库文件备份为带时间戳的副本
- **AND** 应用删除并重建数据库，正常进入主界面且不崩溃
- **AND** 备份文件保留在数据库目录中供用户手动恢复

#### Scenario: 备份动作自身失败

- **GIVEN** 数据库打开失败且备份复制因文件系统异常无法完成
- **WHEN** 应用执行重建兜底
- **THEN** 删库重建流程继续执行且应用正常启动
- **AND** 备份失败被记录到诊断日志

#### Scenario: 升级后实体声明与 schema 一致

- **GIVEN** 新版本修改了配置档实体的可空列声明
- **WHEN** 老版本数据库执行 Room 迁移
- **THEN** 实体列声明与导出 schema 的默认值一致
- **AND** 迁移不因默认值缺失而失败

### Requirement: 批量订阅更新并发执行且互不取消

批量更新全部订阅 MUST 并发执行，单个订阅更新失败 MUST NOT 取消或中断其余订阅的更新。订阅更新执行器 MUST 使用容错作用域隔离各订阅的失败；重复触发同一订阅的更新 MUST 安全返回而不抛出取消异常。后台自动触发的批量更新失败 MUST NOT 弹出用户界面提示（仅记录日志）；用户手动触发的更新失败 MUST 报告可读错误。批量更新结束后 MUST 向用户汇总成功与失败数量。

#### Scenario: 批量更新中单个订阅失败

- **GIVEN** 用户存在多个订阅并执行批量更新
- **WHEN** 其中一个订阅下载失败
- **THEN** 其余订阅继续完成更新
- **AND** 更新结束后用户看到成功与失败的数量汇总

#### Scenario: 后台自动更新失败

- **GIVEN** 订阅自动更新在后台触发且某订阅失败
- **WHEN** 更新流程结束
- **THEN** 失败仅记录到诊断日志
- **AND** 不弹出任何用户界面提示

#### Scenario: 重复触发同一订阅更新

- **GIVEN** 某订阅正在更新中
- **WHEN** 用户再次触发该订阅的更新
- **THEN** 第二次触发安全返回且不产生取消异常
- **AND** 正在进行的更新不受影响

### Requirement: 订阅 diff 熔断与顺序稳定

订阅更新对比新旧节点列表时，若既有节点数不少于 10 且新节点数低于既有节点数的 70%，MUST 跳过删除操作（仅记录警告日志），防止订阅源异常返回导致节点被批量清空。正常 diff MUST 按节点名顺序匹配既有实体，保持节点排序（userOrder）随订阅顺序稳定更新；新增节点 MUST 先收集后逐条入库。订阅下载在代理路径失败时 MUST 回退直连网络重试（每个候选 User-Agent 内独立回退），直连成功的结果被正常采用。

#### Scenario: 机场异常返回少量节点

- **GIVEN** 某订阅当前有 20 个节点，某次更新仅返回 5 个节点
- **WHEN** 应用执行订阅更新
- **THEN** 应用跳过删除既有节点
- **AND** 记录节点数骤降的警告日志

#### Scenario: 正常更新保持节点顺序

- **GIVEN** 订阅更新返回的节点列表与既有节点部分重叠
- **WHEN** 应用完成 diff 与入库
- **THEN** 既有节点的显示顺序与新订阅中的顺序一致
- **AND** 未变化的节点不被重复写入

#### Scenario: 代理路径下载失败回退直连

- **GIVEN** 应用处于连接状态且订阅服务器经代理路径访问失败
- **WHEN** 应用执行订阅更新
- **THEN** 应用自动改用直连网络重试同一候选 User-Agent
- **AND** 直连成功时订阅内容被正常解析与导入

### Requirement: 剪贴板导入仅在订阅启用去重时去重

剪贴板导入节点时，MUST 仅当目标分组或当前分组的订阅配置显式启用去重（`deduplication == true`）时才对解析结果执行去重；未启用时 MUST 保留全部解析出的节点。去重判定 MUST 基于协议凭据与关键传输特征（如 uuid、密码、传输路径、SNI、Reality 公钥、私钥、对端公钥、本地地址、用户名密码等按协议适用项）与最终端口（Hysteria 多端口取首个端口），MUST NOT 依赖节点名参与哈希，确保同服务器不同凭据的节点不被错误合并、同名不同凭据的节点不被漏合并。

#### Scenario: 订阅未启用去重时导入重复节点

- **GIVEN** 目标分组的订阅未启用去重
- **WHEN** 用户通过剪贴板导入包含重复节点的文本
- **THEN** 全部节点被导入为独立配置档

#### Scenario: 订阅启用去重时导入重复节点

- **GIVEN** 目标分组的订阅已启用去重
- **WHEN** 用户通过剪贴板导入包含凭据完全相同的重复节点的文本
- **THEN** 重复节点被合并，仅保留一份

#### Scenario: 同名不同凭据的节点

- **GIVEN** 导入文本中存在节点名相同但 uuid 或密码不同的两个节点
- **WHEN** 应用执行去重判定
- **THEN** 两个节点均被保留为独立配置档

### Requirement: Fragment 提示在宿主不可用时安全降级

Fragment 层的用户提示（snackbar）MUST 在宿主 Activity 不可用、正在结束或已销毁时安全降级（回退到当前前台 Activity 的窗口或 Toast），MUST NOT 因 `requireActivity()` 抛出异常导致进程崩溃。主题属性颜色解析在 attribute 缺失或解析失败时 MUST 返回安全默认值而非抛出异常。文件导出操作 MUST 使用可用的 Context 解析 ContentResolver，宿主缺失时回退应用级 Context。

#### Scenario: 批量更新完成时宿主页面已退出

- **GIVEN** 批量订阅更新在后台完成时宿主 Fragment 已 detach
- **WHEN** 应用尝试显示更新结果提示
- **THEN** 提示经安全路径降级显示或被跳过
- **AND** 不产生 Fragment 未附加导致的未处理异常

#### Scenario: 主题属性缺失时解析颜色

- **GIVEN** 当前主题未定义某个被查询的颜色属性
- **WHEN** 应用解析该属性颜色
- **THEN** 返回透明色等安全默认值
- **AND** 不抛出资源解析异常

### Requirement: 协议连通性兼容修复

Hysteria 节点的 SNI 为空时 MUST 回退使用服务器地址作为 TLS server_name；h2 协议版本的 ALPN MUST 从节点配置解析而非硬编码；Hysteria 与 TUIC 的 outbound TLS MUST 启用 UDP 分片；TUIC 的 SNI 回退 MUST 尊重用户的禁用 SNI 设置。Hysteria 多端口首端口提取 MUST 兼容 `port-range`（`-` 分隔）格式。Shadowsocks v2ray-plugin 的 plugin_opts MUST 显式包含 `mux=0`（用户未显式配置 mux 时），空 plugin_opts MUST 置空而非发射空串。vmess/vless 链接 query 中包含未编码特殊字符（花括号、引号、空格、竖线、反斜杠、尖括号等）时 MUST 经百分号编码重试解析并回退 URI 解析，解析失败报告可读错误；kcp headerType 非法值 MUST 回退 `none` 而非中断解析；xhttp extra 参数转换失败 MUST 保留原文。

#### Scenario: SNI 为空的 Hysteria2 节点

- **GIVEN** 一条未填写 SNI 的 Hysteria2 节点
- **WHEN** 应用生成连接配置
- **THEN** TLS server_name 使用服务器地址
- **AND** 配置可被官方内核接受

#### Scenario: v2ray-plugin 未配置 mux

- **GIVEN** 一条使用 v2ray-plugin 且未显式配置 mux 的 Shadowsocks 节点
- **WHEN** 应用生成连接配置
- **THEN** plugin_opts 包含 `mux=0`
- **AND** 握手不因缺失 mux 参数而异常

#### Scenario: 带特殊字符 query 的 vless 链接

- **GIVEN** 一条 query 参数包含未编码花括号或引号的 vless 分享链接
- **WHEN** 应用解析该链接
- **THEN** 经百分号编码重试后成功解析出节点参数
- **AND** 不产生未处理异常

#### Scenario: 非法 kcp headerType

- **GIVEN** 一条 kcp 传输且 headerType 值不受支持的分享链接
- **WHEN** 应用解析该链接
- **THEN** headerType 回退为 `none`
- **AND** 节点其余参数正常解析
