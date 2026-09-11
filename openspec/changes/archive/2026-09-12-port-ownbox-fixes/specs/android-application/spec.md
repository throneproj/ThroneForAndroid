## ADDED Requirements

### Requirement: 订阅下载按候选 UA 链回退

订阅更新 MUST 依次使用候选 User-Agent 链下载订阅内容：用户为该订阅自定义的 UA（若非空）优先，其后依次为 `clash-meta`、`v2rayN/7.8.2`、`Throne/1.0.0`、`sing-box/1.14.0`。当某个 UA 下载失败或解析出零个节点时 MUST 继续尝试下一个候选；仅当全部候选均失败时订阅更新才失败，且错误信息保留最后一次失败的原始原因。任一候选成功时，MUST 采用该次响应中非空的 `Subscription-Userinfo` 更新流量信息，并在分组名为默认订阅名时使用非空的 `content-disposition` 文件名重命名分组。订阅下载客户端 MUST NOT 依赖 HTTP/3 直连尝试路径。

#### Scenario: 首选 UA 返回空节点时回退

- **GIVEN** 某订阅服务器对默认 UA 返回空内容或无法解析的响应
- **WHEN** 应用执行订阅更新
- **THEN** 应用按候选链依次更换 User-Agent 重试下载与解析
- **AND** 第一个能解析出非空节点列表的 UA 的结果被采用
- **AND** 该响应中的流量信息与文件名（若非空）被正常记录

#### Scenario: 全部候选 UA 失败

- **GIVEN** 所有候选 User-Agent 的下载或解析均失败
- **WHEN** 应用执行订阅更新
- **THEN** 订阅更新以失败结束
- **AND** 用户看到最后一次失败的原始可读错误

### Requirement: 订阅文本解析按 scheme 感知切分且逐条容错

订阅文本解析 MUST 先按行拆分并过滤空行；仅当一行中按已知协议 scheme 统计出多个链接时才按空格进一步切分。`clash://install-config?` 与 `sn://subscription?` 前缀 MUST 仅在整段文本为单条链接时触发订阅跳转语义，混合多节点文本中的此类行 MUST 被跳过而非中断解析。Clash YAML 的 proxies 列表 MUST 逐条独立解析（单条失败仅记录日志并跳过，type 匹配不区分大小写）；JSON 与 WireGuard 配置分支在解析结果为空时 MUST 继续尝试后续解析分支。行级解析与逐链接解析均产出节点时 MUST 优先采用行级解析结果。

#### Scenario: 单行包含多个分享链接

- **GIVEN** 订阅文本某一行包含多个以空格分隔的协议分享链接
- **WHEN** 应用解析该订阅文本
- **THEN** 每个链接被独立解析为节点
- **AND** 不会因整行无法按单链接解析而丢弃节点

#### Scenario: 混合文本中的订阅跳转链接

- **GIVEN** 订阅文本同时包含普通节点链接和一条 `clash://` 订阅链接
- **WHEN** 应用解析该文本
- **THEN** 普通节点被正常解析
- **AND** 不因 `clash://` 链接触发订阅跳转而中断

#### Scenario: YAML 中存在单条坏节点

- **GIVEN** Clash 订阅的 proxies 列表中某一条节点字段非法
- **WHEN** 应用解析该订阅
- **THEN** 其余合法节点全部导入
- **AND** 该条失败被记录到诊断日志

### Requirement: 分享链接解析容忍 fragment 与畸形输入

vmess/vless/trojan 分享链接解析 MUST 先剥离 `#` fragment 并将其 URL-decode 结果作为节点名候选，再对去除 fragment 后的链接做 URL 解析；URL 解析失败 MUST 报告可读错误而非抛出未处理异常。snell 链接在标准 URL 解析失败时 MUST 回退到 regex 解析（支持 `psk@host:port` 形式与 query 参数别名）。hysteria 端口跳跃列表 MUST 在生成配置前规范化（区间分隔符统一、去除空白、校验数字），非法片段被丢弃。`wireguard://` 与 `awg://` URI MUST 被解析为 WireGuard 配置（支持 base64 编码的整段配置回退与 AWG 混淆参数识别），解析失败时返回可读错误而非崩溃。

#### Scenario: 带中文节点名的 vless 链接

- **GIVEN** 一条 fragment 为 URL 编码中文名称的 vless 分享链接
- **WHEN** 应用解析该链接
- **THEN** 节点名称显示为解码后的中文名
- **AND** 连接参数解析结果与不含 fragment 的等价链接一致

#### Scenario: 畸形 vmess 链接

- **GIVEN** 一条无法解析为合法 URL 的 vmess 分享链接
- **WHEN** 应用解析该链接
- **THEN** 应用报告可读的解析失败信息
- **AND** 不产生未处理异常或进程崩溃

#### Scenario: 非标准 snell 链接

- **GIVEN** 一条不符合标准 URL 编码的 `snell://` 分享链接
- **WHEN** 应用解析该链接
- **THEN** 回退解析提取出服务器、端口、psk 与可选参数
- **AND** 生成的节点可正常构建配置

#### Scenario: AWG 分享链接

- **GIVEN** 一条带 AWG 混淆参数的 `awg://` 分享链接
- **WHEN** 应用解析该链接
- **THEN** 生成的 WireGuard 节点携带混淆参数并以兼容标记命名
- **AND** 该节点可参与正常的连接与测速流程

### Requirement: 节点去重区分同服务器不同凭据

订阅导入与节点保存的去重判定 MUST 在服务器地址与端口之外纳入协议凭据与关键传输特征（如 uuid、密码、传输路径、SNI、Reality 公钥、节点名等按协议适用项），确保同一服务器上凭据不同的节点不被错误合并或覆盖。

#### Scenario: 同服务器不同凭据的两个节点

- **GIVEN** 订阅中存在服务器地址与端口相同但 uuid 或密码不同的两个节点
- **WHEN** 应用完成订阅解析与去重
- **THEN** 两个节点均被保留为独立配置档
- **AND** 各自的凭据与传输设置保持不变

### Requirement: 配置档数据库打开失败时安全重建

配置档数据库初始化 MUST 先尝试正常打开；打开失败时 MUST 记录原始错误、删除损坏的数据库文件并重建空数据库，使应用可以继续启动而不是陷入崩溃循环。数据库降级打开 MUST 配置破坏性降级兜底。可空协议 Bean 列的 Room 实体声明 MUST 与导出 schema 的列默认值一致（新增可空列 MUST 声明 NULL 默认值），避免升级迁移因 schema 不一致而崩溃。

#### Scenario: 数据库文件损坏后启动

- **GIVEN** 设备上配置档数据库文件损坏导致打开抛出异常
- **WHEN** 用户启动应用
- **THEN** 应用记录原始错误后删除并重建数据库
- **AND** 应用正常进入主界面且不崩溃

#### Scenario: 升级引入新的可空 Bean 列

- **GIVEN** 新版本为配置档表新增可空协议 Bean 列
- **WHEN** 老版本数据库执行 Room 迁移
- **THEN** 实体列声明与导出 schema 的默认值一致
- **AND** 迁移不因默认值缺失而失败