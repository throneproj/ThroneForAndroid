# Android Application Specification

## Purpose

定义 Android 应用层的主要职责、关键用户能力以及与 libcore 之间必须保持的行为契约。

## Requirements

### Requirement: Android 模块职责分层

`app/` MUST 承载 UI、后台服务、数据库、配置解析和 JNI 平台适配。`io.nekohasekai.sagernet` MUST 保持 SagerNet 核心框架职责，`moe.matsuri.nb4a` MUST 承担 NekoBox/Throne 兼容扩展、附加协议绑定和工具职责。资源 MUST 按 Android 标准目录组织，并维持已支持语言的本地化字符串；测试菜单和设置中的用户可见名称 MUST 使用可翻译资源，不得以不可翻译的英文文案替代。

#### Scenario: 添加 Android 功能

- **GIVEN** 一个新功能需要 UI、持久化或 libcore 平台能力
- **WHEN** 实现该功能
- **THEN** 代码按核心框架与兼容扩展的职责放入对应包
- **AND** 用户可见文本进入资源并同步适用语言

#### Scenario: 显示本组测试入口

- **GIVEN** 用户打开配置列表的测试菜单
- **WHEN** 应用展示 URL 延迟测试与速度测试入口
- **THEN** 当前语言显示“URL 测试本组”和“速度测试本组”的等义本地化名称
- **AND** 不显示硬编码或声明为不可翻译的旧英文 URL 测试文案

### Requirement: sing-box 配置符合官方 schema

配置生成 MUST 以当前 `SINGBOX_VERSION` 对应的官方 schema 为准。入站嗅探与目标解析 MUST 使用位于路由规则前部的 `sniff`/`resolve` 动作；TUN 地址 MUST 使用合并后的 `address` 字段；双网络加速 MUST 映射为 `default_network_strategy: "hybrid"`。已被官方移除的 legacy 字段 MUST NOT 被发射。

#### Scenario: 生成正式连接配置

- **GIVEN** 用户启用流量嗅探、IPv6 或双网络加速等设置
- **WHEN** `ConfigBuilder` 生成 sing-box 配置
- **THEN** 输出可被目标官方内核 schema 接受
- **AND** 不包含已移除的入站 sniff、legacy TUN 地址或 `route.concurrent_dial` 字段

### Requirement: 链式配置引用必须解析到最终出站标签

Android 配置生成器 MUST 在写入链式 detour、selector 成员或最终路由引用前确定被引用节点的最终标签。当同一节点既作为独立规则出站又作为另一条链的成员时，生成配置中的全部引用 MUST 指向实际存在的 endpoint 或 outbound 标签；构建顺序 MUST NOT 产生悬空引用。

#### Scenario: 共享节点先于包含它的链生成

- **GIVEN** 当前主节点是普通节点，路由规则同时引用共享节点和包含该共享节点的链式代理
- **WHEN** 应用生成正式连接配置，且共享节点先于链式代理生成
- **THEN** 链中前一跳的 detour 指向共享节点已生成的最终标签
- **AND** 最终配置不存在无法解析的 endpoint、outbound、selector 或 route final 引用

#### Scenario: 包含共享节点的链先生成

- **GIVEN** 当前主节点本身是包含共享节点的链式代理
- **WHEN** 应用生成正式连接配置
- **THEN** 链中的每个 detour 指向实际生成的后继标签
- **AND** 后续规则复用该节点时不得产生重复或悬空标签

### Requirement: 服务启动失败不得导致应用进程崩溃

Android 后台服务 MUST 将配置无效、依赖缺失或内核启动失败报告为服务启动失败，并 MUST 清理已部分初始化的 VPN、插件和 box 资源。清理已关闭资源时产生的可识别关闭完成状态 MUST NOT 作为主线程未处理异常导致 `:bg` 进程崩溃；原始启动错误 MUST 保留为用户可读错误和诊断日志。

#### Scenario: 内核启动回滚后服务执行清理

- **GIVEN** sing-box 在部分启动后因配置依赖错误失败，并已回滚关闭部分资源
- **WHEN** Android 服务执行失败清理
- **THEN** 服务进入停止状态且不触发崩溃处理器
- **AND** 用户看到原始启动失败原因，而不是后续的已关闭资源错误
- **AND** 清理阶段的异常状态被记录用于诊断

### Requirement: DNS 路由避免意外泄露

本机直解场景中的 `hosts` MUST 归一化为 `local`。远程 DNS 遇到 `hosts`、`local`、`localhost` 或 `fakeip` 占位符时 MUST 回退至明确的远程 DoH 地址并提示用户；远程 DNS MUST 显式经当前代理节点 detour。订阅自定义服务器 DNS 的 UI MUST 保持暂停开放，直到上游语义明确且强制解析、批量测速、正式连接三条路径能够一致实现。

#### Scenario: 用户把本机解析器配置为远程 DNS

- **GIVEN** 远程 DNS 设置为本机解析占位符
- **WHEN** 生成连接配置
- **THEN** 配置改用安全的远程 DoH 回退值
- **AND** DNS detour 指向当前节点
- **AND** 用户收到修改设置的提示

### Requirement: 测速与正式连接保持语义一致且实例隔离

URL 延迟测试 MUST 使用不注册 PlatformLogWriter 的测试实例，从而不创建或共享 `cache.db`。延迟与速度测试 MUST 沿用用户的 IPv6 模式和 outbound `domain_strategy`，并经受测节点对应的默认 outbound 建立连接。URL 延迟测试 MUST 预热后复用连接测量 RTT；速度测试 MUST 按用户选择执行下载+上传、仅下载、仅上传或简单下载，并持续提供每个节点的进度、最终吞吐量、错误和取消状态。成功完成的速度测试结果 MUST 按 profile 持久落地，在节点卡片右下角现有测速信息区域以灰色辅助文字显示；结果 MUST 在进度对话框或通知关闭、列表重绑/刷新及重新进入配置列表后继续可见，直到用户清除测试结果或后续速度测试覆盖该节点结果。吞吐量 MUST 使用专用结果字段或模型，MUST NOT 写入毫秒语义的 `ProxyEntity.ping` 字段。每个 box MUST 拥有独立的平台接口 wrapper，默认接口监视器 MUST 在首拨前同步注册，fd protect 对非 VPN 未运行类故障 MUST fail-fast。配置列表 MUST 提供“URL 测试本组”和“速度测试本组”操作，并 MUST NOT 提供批量直连 TCP Ping 或 ICMP Ping 操作。新安装或尚未持久化对应设置时，延迟测试 URL MUST 默认为 `http://cp.cloudflare.com/`，URL 延迟测试并发数 MUST 默认为 10；Throne 速度测试 MUST 沿用参考版本自己的节点调度和单节点连接并发策略，MUST NOT 使用 URL 延迟并发值 10。下载、上传及简单下载测速默认值 MUST 与采用的 Throne 桌面参考版本一致，并由测试设置和执行路径共同使用。应用升级 MUST 保留用户已持久化的自定义测试 URL、URL 延迟并发数及速度测试设置。

#### Scenario: 并发批量延迟测试

- **GIVEN** 主进程同时测试当前组的多个节点
- **WHEN** 用户执行“URL 测试本组”且测试实例启动并拨号
- **THEN** 各实例不创建共享 cache 文件
- **AND** 各实例拥有正确的网络接口缓存和 protect 结果
- **AND** 测速使用与正式连接一致的协议族与域名策略

#### Scenario: URL 延迟测试使用独立并发配置

- **GIVEN** 主进程同时执行当前组的 URL 延迟测试
- **WHEN** URL 延迟测试工作队列按默认配置启动
- **THEN** 最多 10 个 URL 延迟测试 worker 并发处理节点
- **AND** 该并发值不传递给 Throne 下载/上传速度测试

#### Scenario: 测试菜单不再提供直连 Ping

- **GIVEN** 用户打开当前组的测试菜单
- **WHEN** 菜单完成渲染
- **THEN** 用户可以选择“URL 测试本组”和“速度测试本组”
- **AND** 菜单中不存在批量 TCP Ping 或 ICMP Ping 操作

#### Scenario: 当前组执行下载和上传速度测试

- **GIVEN** 用户选择下载+上传模式且当前组包含多个可测试节点
- **WHEN** 用户执行“速度测试本组”
- **THEN** 每个节点的测速流量均经该节点建立
- **AND** 界面持续显示下载与上传进度
- **AND** 每个成功节点的最终下载与上传吞吐量按 profile 保存，并在对应节点卡片右下角以灰色辅助文字显示
- **AND** 关闭进度界面、刷新列表或重新进入配置列表后，该结果仍然可见
- **AND** 用户清除测试结果或该节点完成下一次速度测试时，旧速度结果被清除或覆盖
- **AND** 吞吐量不写入用于 URL RTT 毫秒值的 `ProxyEntity.ping` 字段
- **AND** 用户取消测试时所有进行中的测速任务停止并报告取消状态

#### Scenario: 当前组执行简单下载测试

- **GIVEN** 用户选择简单下载模式并配置了简单下载 URL
- **WHEN** 用户执行“速度测试本组”
- **THEN** 应用经每个受测节点下载该 URL 的响应体并按实际字节与耗时计算下载吞吐量
- **AND** 不执行上传阶段

#### Scenario: 首次使用测试默认配置

- **GIVEN** 用户是新安装用户或尚未保存过对应测试设置
- **WHEN** 应用读取延迟与速度测试配置
- **THEN** 延迟测试 URL 为 `http://cp.cloudflare.com/`
- **AND** URL 延迟测试并发数为 10
- **AND** 速度测试模式、超时、服务发现/下载/上传 URL 与简单下载 URL 和已记录的 Throne 桌面参考版本默认值一致

#### Scenario: 升级后保留自定义配置

- **GIVEN** 用户在升级前已保存自定义延迟 URL、并发数或速度测试 URL
- **WHEN** 应用升级并加载新版本默认配置
- **THEN** 已保存的自定义值保持不变
- **AND** 新默认值仅用于不存在持久化值的设置

### Requirement: 混合入站行为由 TUN 设置显式控制

TUN 模式下，只要混合入站存在，VPN 服务 MUST 将系统 HTTP 代理指向本地混合端口。用户启用“禁用混合入站”时，配置 MUST 不生成 mixed 入站及其专属路由规则，VPN MUST 跳过系统 HTTP 代理设置，相关设置项 MUST 禁用。用户名为空时混合入站 MUST 免认证；用户名非空时应用内部 HTTP 请求 MUST 跟随运行时认证信息。

#### Scenario: TUN 模式禁用混合入站

- **GIVEN** 用户在 TUN 模式开启禁用混合入站
- **WHEN** 服务构建配置并启动 VPN
- **THEN** mixed 入站和专属规则不存在
- **AND** VPN 不调用系统 HTTP 代理设置
- **AND** 代理端口、认证和绕过列表设置显示为不可用

### Requirement: 桌面备份导入保持核心数据语义

Android 应用 MUST 能导入 Throne 桌面 `.thrbackup`：解析 `THRN` QDataStream 与内嵌 SQLite，忽略图标，尽力导入配置档、分组、路由和可映射设置。sing-box outbound SHOULD 优先还原为原生 Bean，失败时回退 `ConfigBean`。导入完成后 MUST 触发完整重启。

#### Scenario: 导入有效桌面备份

- **GIVEN** 用户选择有效的 `.thrbackup`
- **WHEN** 导入器完成解析
- **THEN** 支持的配置档、分组、路由和设置被映射到 Android 数据模型
- **AND** 不支持的 outbound 以自定义 JSON 配置保留
- **AND** 应用触发完整重启

### Requirement: 启动入口与关键 UI 保持可达

Launcher `MainActivity` MUST 唯一声明静态快捷方式元数据；切换、启用、禁用和扫码四个快捷方式的目标包 MUST 与 `nb4a.properties` 的应用包名一致，目标 Activity MUST 可被 Launcher 启动。扫码界面 MUST 以底部悬浮按钮提供手电筒和图片导入，不依赖会被预览遮挡的工具栏。

#### Scenario: 校验启动快捷方式与扫码入口

- **GIVEN** 包名或扫码 UI 被修改
- **WHEN** 运行对应静态校验
- **THEN** 四个快捷方式、元数据唯一性和导出状态均合法
- **AND** 扫码界面的两个操作可见且已接线

### Requirement: 用户可见错误对话框安全显示

Android 应用在前台页面操作失败且已有可用宿主 Activity 时，MUST 显示包含原始可读错误消息的提示对话框。对话框使用主题包装 Context 时 MUST 正常解析其宿主，MUST NOT 因 Context 类型转换失败而丢失提示；宿主不存在、正在结束或已销毁时 MUST 安全跳过显示，且 MUST NOT 产生新的未处理异常。

#### Scenario: 规则资源更新失败且页面仍有效

- **GIVEN** 用户在规则资源页面发起更新，页面 Activity 仍处于可显示窗口的状态
- **WHEN** 更新操作失败并生成可读错误消息，且对话框 Context 被主题 wrapper 包装
- **THEN** 应用显示含原始错误消息的错误提示对话框
- **AND** 不产生 Context 到 Activity 的类型转换异常

#### Scenario: 显示错误前宿主页面已退出

- **GIVEN** 后台操作失败后，对话框准备返回主线程显示
- **WHEN** 宿主 Activity 不存在、正在结束或已经销毁
- **THEN** 应用安全跳过错误对话框显示
- **AND** 不产生新的未处理异常或无效窗口

#### Scenario: 配置状态错误提示复用安全显示入口

- **GIVEN** 用户点击处于错误状态的配置项，且宿主页面仍有效
- **WHEN** 应用请求显示该配置项的错误详情
- **THEN** 应用显示含该错误详情的提示对话框
- **AND** 主题包装 Context 不影响显示结果

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
