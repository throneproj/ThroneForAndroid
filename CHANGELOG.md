#### 2.0.6

* Add DNS tunnels: DNSTT/VayDNS, MasterDNS/StormDNS and XDNS **1**
* Add mKCP transport to Xray
* Add S3 storage to XDRIVE
* Improve WARP MASQUE over HTTP/3
* Update sing-box, Xray and Go
* Fix log export crashing in a loop
* Fixes and Improvements

**1**:
Refer to the [documentation](https://throneproj.github.io/advanced/dns_tunnels/) for more details.

#### 2.0.5

* Add per-group routing profiles **1**
* Add routing profile import from subscriptions **2**
* Add AmneziaWG mode to WARP
* Add compact profile rows and compact group cards **3**
* Add Allow apps to bypass VPN **4**
* Allow excluding countries in the Auto Selector **5**
* Start the Auto Selector as soon as enough profiles work
* Reconnect to the replacement when a subscription update removes the running profile **6**
* Add Clear test results and Clear traffic statistics to the profile menu
* Show Xray logs in the app log
* Fix Xray profiles stalling for about a minute after a network change
* Fix Xray XHTTP connection leak
* Fix connections not recovering after a network loss
* Fix AmneziaWG on devices with UDP GSO, again
* Fix the per-app proxy switch not turning off
* Fixes and Improvements

**1**:
A group can connect with its own routing profile instead of the one chosen in the Routing menu. Set it in the group's settings.

**2**:
A subscription that provides a routing profile (Happ format) can save it as its group's own routing profile on every update. Turn it on with Use the subscription's routing profile in the group's settings. DNS settings from the subscription are never used.

**3**:
Turn them on in Settings › Appearance. Compact rows show only the profile's name and latency; compact group cards show a usage bar and the time left instead of the subscription link and details.

**4**:
Off by default, in Settings › General. When on, apps that ask can connect outside the VPN, such as Google push notifications (FCM). An always-on VPN with Block connections without VPN ignores it.

**5**:
Put ! before a country code in Only countries to leave that country out, e.g. !RU uses every country but Russia.

**6**:
When an update replaces the running profile, Throne connects to the single new profile with the same name and type. If there is none, it stays stopped.

#### 2.0.4

* Add DNS settings to routing profiles **1**
* Add enable/disable toggle for route rules
* Extend the Multiplex preset and allow custom per-profile Multiplex settings **2**
* Update Xray and add XDRIVE transport **3**
* Share WebDAV backups with Throne desktop **4**
* Add Resolve domain to the profile menu and resolve through the core
* Improve performance with large profile lists, search, subscriptions and logs
* Make cloning a remote routing profile create a local copy **5**
* Move Speed test current connection to the stats bar's long-press menu
* Update sing-box
* Fix rule-set list crash on older devices
* Fix subscription updates failing while disconnected in Proxy mode
* Fix AmneziaWG on devices with UDP GSO
* Fix status bar exit country differing from the IP & country test
* Fixes and Improvements

**1**:
A routing profile can use its own remote and direct DNS servers instead of the ones in Settings › DNS.

**2**:
Multiplex in a profile is now Keep default, On, Off or Custom. Custom gives the profile its own mode (max streams or max connections), padding and TCP Brutal speeds. Xray Multiplex adds max connections, XUDP concurrency and UDP/443 handling.

**3**:
For security reasons, XDRIVE profiles have no share link; import the server's JSON configuration instead.

**4**:
WebDAV backups now use the same backup list as Throne desktop, so backups from all your devices show up together with their device, platform, app version and contents. Backups can be deleted from the list, downloads are checked against it, and each device can be given a name in WebDAV settings.

**5**:
Every update of a remote routing profile replaces its rules, so edits made to it are lost. Its clone is a local copy that is never updated and keeps your edits.

#### 2.0.3

* Add multi-group Auto Selector **1**
* Add input validation to editors and imports **2**
* Add live graphs
* Add core performance profiling in Settings › Diagnostics
* Add log filter
* Add TLS and HTTP version settings for subscriptions
* Add per-subscription update interval
* Add persistent group sorting
* Add TLS fragment options to route rules
* Add cipher suites and the unsafe fingerprint
* Add AmneziaWG QUIC signature generator
* Allow customizing the exit country lookup **3**
* Keep route rules attached to subscription profiles after updates
* Remove Xray forced deprecations
* Fix Auto Selector with Xray profiles
* Fix subscription auto-update after reboot
* Fix subscription URLs with credentials
* Fix TUIC and Hysteria2 link import
* Fix dashboard on older WebView versions
* Fix WireGuard startup memory usage
* Fixes and Improvements

**1**:
An Auto Selector can now track several groups at once.

**2**:
Editors now point out invalid fields, and imports report what was skipped. Extra Core, Chain and Auto Selector profiles are no longer imported.

**3**:
The exit country lookup can be turned off or switched to another service in Settings › Testing.

#### 2.0.2

* Add sing-box dashboard, bundled and enabled by default **1**
* Add automatic Xray geo asset download (geoip.dat / geosite.dat)
* Add subscription info card to subscription groups
* Add include/exclude name filters to subscriptions
* Add network type (Wi-Fi / mobile data) and metered network conditions to route rules
* Add package name regex and "Unknown apps" to route rules
* Add exit IP and country to the status bar
* Add ECH support for Xray and `ech=` share links
* Add certificate SHA-256 pinning
* Allow connecting without rule-sets that could not be downloaded **2**
* Allow editing the running profile
* Allow changing the DNS inbound port
* Make the system HTTP proxy optional and off by default **3**
* Enforce go Tun stack **4**
* Skip unsupported nodes on import
* Fix URL test with Xray balancer configs
* Fix log level setting being ignored
* Fix ECH DNS loop
* Fix XHTTP/TLS share link import
* Fix IPv6 FakeIP range
* Log URL test errors
* Use a monochrome notification icon
* Fixes and Improvements

**1**:
The in-app Clash panel is replaced by the official sing-box dashboard, served by the core's sing-box API (Settings › Core). It ships with the app and opens from the side menu while connected.

**2**:
When a connection fails because remote rule-sets cannot be downloaded, Throne offers to connect without them. They are then downloaded through the proxy and kept for later connections.

**3**:
The local mixed inbound used to be set as the VPN's system HTTP proxy automatically. It is now a separate switch in Inbound settings, off by default, and unavailable while inbound authorization is on.

**4**:
As on desktop, the go Tun stack of sing-box replaces the system/gVisor stacks, giving better performance and fewer compatibility issues.

#### 2.0.1

* Fixed DNS issues and updated the Throne kernel. (Thanks @Mahdi-zarei)
* Completed i18n.
* Fixed some UI issues.

#### 2.0.0

**ThroneForAndroid v2.0.0 — A New Chapter 🚀**

We are thrilled to announce **v2.0.0**, a milestone release that brings a fundamental architectural shift and deep convergence with the **Throne Desktop** ecosystem.

By transitioning to the unified `throneproj/sing-box` core, this version addresses long-standing legacy limitations, resolves numerous open feature requests, and paves the way for seamless cross-platform feature parity.

**🌟 What's New & Major Highlights**

**1. Architectural Evolution: Core Convergence**
* **Unified Core Logic:** Ported core features directly from Throne Desktop, including embedded Xray support, native speed testing (`urlTest`), and advanced protocol routing.
* **Streamlined Migration:** Re-implemented data nodes and parsing logic to align with Throne Desktop, paving the way for smooth profile import/export between devices.

**2. Cleaner Architecture & Feature Deprecations**
* **Plugin System Removal:** Legacy NekoBox plugins have been officially deprecated and removed. With our comprehensive built-in core capabilities, external plugins are no longer necessary, drastically reducing maintenance overhead and improving security.

**3. Android-Native Enhancements**
* Maintained Android-exclusive workflow essentials such as **Per-App Proxy / Bypass Rules**.
* Preserved the intuitive mobile UI while running a desktop-grade networking engine under the hood.

**⚠️ Important Breaking Changes & Migration Notes**

* **New Signing Key & Package Conflict:** Starting with v2.0.0, release APKs are signed with a new signing key to mark a clean separation from pre-v2 builds. Since the package name remains `com.nb4a.throne`, installing v2.0.0 directly over a pre-v2 release will trigger a signature conflict error. **You must uninstall the older release before installing v2.0.0.**
* **Dual Installation & Migration Tip:** If you need to keep your old configuration accessible side-by-side during the migration, the legacy preview debug builds do **not** conflict with the new release key. You can download the last legacy debug APK from [GitHub Actions Run #34730820603](https://github.com/throneproj/ThroneForAndroid/actions/runs/34730820603) to run both versions simultaneously and export your profile details.
* **Config Reset:** Due to sweeping changes in config parsing and node structures, legacy local configurations will not auto-migrate. Please re-import your subscription/profiles upon upgrading.
* **Network Stack Recommendation:** System stack (LWIP/System) is currently undergoing refinement. We strongly recommend using **`gvisor`** as your primary TUN network stack in this release for optimal stability.

**Acknowledgments**

A massive thank you to all contributors and community members for testing, reporting issues, and helping us shape the future of **ThroneForAndroid**!

#### 1.6.4

* 手动颜色主题加入了纯白/暗夜模式，并兼容OLED开关开启纯黑版本
* 主题修改即时生效
* 支持订阅并发更新
* 合并了Own v2.6.0中IPv6等多项修复性改进

#### 1.6.3

* 增加了`OLED暗色模式`
* **尝试**吸纳了[OwnBox v2.5.0](https://github.com/Own716/OwnBoxForAndroid)中对Snell、TLS分片、Hysteria等多项修复性改进

T4A更新至此，已经积累了大量改动。为了确保不因为配置残留导致的异常，本次更新上移了软件版本号。
这意味着：
* 更新后你的订阅和规则会丢失，请**提前导出备份文件**！
* 退回老版本必须卸载重装。

#### 1.6.2

* 修复了关于页URL问题

#### 1.6.1

* **加入了`XHTTP`支持**
* 加入了`自定义图标`功能 **1**
* 修复了切换UI有概率闪退的bug
* 调整了UI细节
* 调整了副版本命名格式：`pc[当前对齐的Throne版本号]-[基于此第几次修改]`

**1**:
受限于Android应用规范，图标替换以创建快捷方式的方式实现。

#### 1.5.11

* 改进了`授权协议`的UI
* 此release标志着新Action工作流运行成功

#### 1.5.10

* 修复了`链式代理`相关的节点切换导致的闪退问题
* 修复了关于页的UI逻辑问题
* 规范LICENSE为`GPL v3.0`正文本身
* 完善了日志记录

🎉ThroneForAndroid（T4A）已正式转移至 `throneproj` 组织名下！ 🚀

即日起，T4A 将作为 Throne 生态的官方 Android 客户端 开展后续迭代。我们将与桌面端进一步紧密协同，持续推进跨平台功能对齐与优化，为大家带来更加统一、丝滑的使用体验。特别感谢 Throne 开发组与社区每一位小伙伴的支持！

#### 1.5.9

* 同步了Throne v1.2.4的`URL 测速`解决方案
* 移除了TCPing和ICMPing代码残留

#### 1.5.8

* 修复了路由资源更新toast不显示的问题
* 改进了OpenSpec执行效率

#### 1.5.7

* 新增了WireGuard协议支持

#### 1.5.6

* 优化了 `扫描二维码` 页面布局，加入了从相册导入的功能。

#### 1.5.2

* **尝试**修复了数据/WiFi切换导致的界面卡顿问题

#### 1.5.1

* 增强了日志记录，方便后继debug
* 完善了singbox内核CI/CD构建

#### 1.5.0

* 备份工具加入了**从Throne电脑版导入**选项，初步兼容Throne v1.2.3的`.thrbackup`格式
* 优化了默认的`屏蔽 QUIC`规则，使其更精确
* 修复了备份页的文字UI细节

#### 1.4.12

* 更新内核到singbox v1.13.16，对齐Throne v1.2.3

#### 1.4.11

* 优化了代码，缓解了订阅自动更新失败的问题
* 进一步清理了移植官核的残留冲突代码
* **尝试**增强了长时间使用的稳定性

#### 1.4.9

* 重构了`URL Test`相关代码，改善了部分订阅中的异常问题。

#### 1.4.8

* 改进了关于`URL Test`的日志保留
* 进一步优化了`URL Test`实现
* **尝试**修复有些订阅会更新失败问题

#### 1.4.7

优化了HTTP代理逻辑，向Throne对齐：
* `禁用混合入栈`就是纯TUN
* 关闭`禁用混合入栈`时就是`127.0.0.1`可连接的
* 允许用户自行设置账密

#### 1.4.6

* 修复了`日志级别`失控的问题

#### 1.4.5

* 解决了`URL Test`闪退的问题

#### 1.4.3

Throne for Android（T4A）从今日起使用sing-box官方内核！

* **大量重构**了内核接驳代码，切换到了Throne同款的sing-box v1.13.15
* 解决了原neko魔改内核导致的dns路由泄露问题
* **暂时**移除了官核没有的并发拨号功能
* **疑似**加入了双网络加速功能
* sniff方式同步sing-box最新版：`探测结果用于目标地址`已废弃
* 路由规则兼容Throne系命名（如`geosite-cn`）
* 简化版本号命名方式

#### 1.4.2-m20-16

* 为预览版提示加入`不再显示`功能

#### 1.4.2-m20-15

* 重构了订阅解析，支持sing-box系订阅加载 **1**
* 调整了默认UA，默认请求Singbox系订阅

**1**:
由于个人订阅节点种类有限，此次改动在一大半的节点种类上尚未经过测试，有bug请及时反馈！

#### 1.4.2-m20-14

* **加入了`禁用混合入站`功能**，Throne同款
* 修复了版本更新检查异常
* 疑似修复了订阅自动更新失效 **1**
* 修复了按键导航栏在某些取色下会不恰当变黑的问题

**1**:
长期稳定性需要大家众测一下，如果稳定没问题请在下方点点赞，谢谢！

#### 1.4.2-m20-10

* 重构HTTP后端，兼容HTTP/2节点

#### 1.4.2-mod-20

本分支正式以Throne for Android命名！

* 同步了Throne的名字和logo
* 合入上游1.4.2-mod-20的UI改进
* 移除了关于页非必要的内容

#### 1.4.2-m20-9

* 调整版本号格式，方便独立feat迭代
* 修复了“路由”和“规则”页在启用SagerNet底栏时挡住最下方内容的问题

#### 1.4.2-mod-19

* 加入了Android 12+的系统主题色同步功能
* 合并上游rules解决方案（2026.07.26）
