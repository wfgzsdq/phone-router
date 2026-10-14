# 热点控制工具 (phone-router)

为已 root 且安装 LSPosed 的安卓手机开发的**热点控制 APK + Xposed 模块**：开机自动开启共享移动数据的 WiFi 热点，并提供一个内置网页管理端，连上热点的任意设备用浏览器即可管理热点。通过移植 SoftApHelper 的系统 Hook，将 Wi-Fi 热点网关设为 `192.168.43.1/24`。

> 原 root 补设 IP 版本的历史验证设备：Redmi K40 (alioth) · Android 13 · HyperOS V816 · KernelSU 4.2 (ReSukiSU)。当前 LSPosed 固定 IP 版本需在启用模块、设置作用域并重启后另行验证，不能沿用旧版验证结论。

## 功能

- **真·移动数据共享热点**：以 system uid 调用系统 `TetheringManager` 开/关 WiFi 热点，连上的设备通过手机 SIM 上网（`ip_forward=1`，NAT 由系统接管）。
- **开机自启**：双保险——APK 内 `BOOT_COMPLETED` 接收器 + 一键安装的 KernelSU 开机模块（`/data/adb/modules/phonerouter_boot`），绕过 MIUI/HyperOS 的自启限制。
- **固定网关 192.168.43.1（LSPosed）**：采用 [SoftApHelper](https://github.com/XhyEax/SoftApHelper) 的固定 IP Hook，在系统选择 Wi-Fi 热点地址时返回固定地址，让后续接口和 DHCP 配置使用该地址；已移除 `ip addr add` 补设路径。只移植 Wi-Fi 固定 IP，不包含上游 USB/蓝牙地址修改、隐藏热点类型或锁定 5G 信道功能。
- **网页管理端**（参考 UFI-TOOLS，裁剪为热点相关子集，分页式）：
  - **首页**：热点状态、运行时长、热点上/下行流量、剩余流量、在线设备数、热点开关、重启、在线设备（IP/MAC/状态，一键拉黑/解除）
  - **WiFi**：SSID、密码、加密方式(开放/WPA2/WPA2-3/WPA3)、频段(2.4G/5G)、最大连接数、隐藏网络、扫码连接二维码
  - **流量**：流量余量（本周期已用 / 剩余 / 进度条）、设置月套餐总量与结算日、限速（上/下行 Mbps）、定时开关（按时间+星期自动开/关热点）
  - **短信**：双卡选择、收件箱/已发送查看、发送短信
  - **文件**：浏览 `/storage/emulated/0`、上传/下载、新建文件夹、改名、删除
- **流量余量**：以 system uid 调用系统 **NetworkStatsManager** 读取本计费周期**已用**移动流量，后台**每 30 分钟**自动刷新；结合用户设置的月套餐总量算出**剩余**。
- **限速**：用 `tc` 对热点整体限速（egress HTB 限下行 + ingress policing 限上行），热点重启后自动恢复。
- **定时开关**：AlarmManager 精确闹钟，按时间/星期自动开或关热点，重启后自动重排。
- **短信**：读取用 root `content query`（免 READ_SMS），发送优先使用 system UID shim，失败后回退 SmsManager（需 SEND_SMS）。
- **文件管理**：原生 `java.io.File` 读写 `/storage/emulated/0`，无全文件权限时自动用 root 兜底。
- **访问控制**：网页端密码登录（默认 `admin`，请在 App 内修改），token 鉴权。

## 架构

```
APK (com.phonerouter.hotspot, Kotlin)
 ├─ MainActivity        控制界面 / root 状态 / 自启开关 / 装开机模块
 ├─ HotspotService      前台服务：跑 Web 服务 + 开机拉起热点
 ├─ BootReceiver        BOOT_COMPLETED → 启动服务
 ├─ root/RootShell      su / su 1000 执行器
 ├─ root/PrivClient     部署 shim、以 system uid 调用并解析 JSON
 ├─ hotspot/TetherController   开 / 关 / 重启热点
 ├─ hotspot/SoftApConfigManager/HotspotManager  读写配置、状态快照
 ├─ xposed/SoftApHook   LSPosed 注入系统/Tethering：固定 Wi-Fi 网关
 ├─ xposed/HookSupport  接口类型 / 调用栈 / 网段冲突检查
 ├─ hotspot/ClientsManager     在线设备(ip neigh + 租约) / 黑名单
 ├─ web/WebServer       NanoHTTPD：静态页 + REST API + token 鉴权
 ├─ assets/web/         网页管理端(原生 HTML/CSS/JS + qrcode.js)
 ├─ assets/shim.jar     特权 shim（system uid 运行）
 └─ assets/xposed_init  Xposed 模块入口（随 APK 安装）
KsuModule               一键写入 KernelSU 开机模块
```

### 特权调用原理

普通 App 无法调用 `TetheringManager.startTethering` / `WifiManager.setSoftApConfiguration`（隐藏 API + 需签名级权限）。本项目把这些调用放进一个纯反射的 shim（`shim/src/.../Main.java`），用 root 以 **system uid(1000)** 运行：

```
su 1000 -c "CLASSPATH=/data/local/tmp/phonerouter_shim.jar \
  app_process /system/bin com.phonerouter.shim.Main <cmd> [k=v ...]"
```

以 system uid 运行时隐藏 API 黑名单不生效、`TETHER_PRIVILEGED` 等权限齐备。shim 通过 `ActivityThread.systemMain()` 取得系统 Context，再反射调用框架 API，结果以 JSON 从 stdout 返回。shim 命令：`get-config` / `set-config` / `tether-start` / `tether-stop` / `block-client` / `unblock-client`。

## 环境要求

- 手机：Android 10+（`minSdk=29`），已 root（KernelSU / Magisk 均可，`su` 支持 `su 1000` 切 uid）。
- 固定 IP：需要可用的 LSPosed，启用本 APK 的“热点控制”模块并设置下述作用域。Root 仍用于热点控制、短信、文件及限速；LSPosed 专门负责固定 IP。
- 构建：JDK 17、Android SDK（platform android-34、build-tools 34.0.0）。

## 构建

```bash
# 配置 ANDROID_HOME 或 local.properties 后构建，shim 由 Gradle 自动编译
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> `local.properties` 里的 `sdk.dir` 指向你的 Android SDK。

Xposed API 82 从 `https://api.xposed.info/` 获取，仅作为 `compileOnly` 依赖，不打包进 APK。无需单独构建或安装 SoftApHelper。固定 IP Hook 及推荐作用域随本 APK 一同安装。

验证命令：`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`。主机单元测试覆盖 Wi-Fi 类型筛选、调用栈筛选、Android 11/12+ 冲突检查和 ROM 字段变更时的回退，不代表 LSPosed 注入或真机 DHCP 已验证。

## 安装与首次使用

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 在 **LSPosed → 模块 → 热点控制** 中启用本模块。作用域勾选 **系统框架（`android`）**，以及本机存在的下列推荐 Tethering 包；不是把“热点控制”自身选为被 Hook 的应用。
   - `com.android.networkstack.tethering.inprocess`
   - `com.android.networkstack.tethering`
   - `com.google.android.networkstack.tethering.inprocess`
   - `com.google.android.networkstack.tethering`
2. **重启手机**，让系统/Tethering 进程加载模块。升级 Hook 代码或调整作用域后同样需要重启。
3. 打开 **热点控制** App，在 KernelSU/Magisk 中授予 root 权限，确认 App 显示 `Root: 已获取`。
4. 打开「热点开关」，按需安装 KernelSU 开机自启模块。
5. 用另一台设备连上热点，确认分配到 `192.168.43.x/24`、网关为 `192.168.43.1`，再访问 **`http://192.168.43.1:8080`**。默认密码为 `admin`，可在 App 修改。

原“固定网关”开关已移除，固定 IP 随 LSPosed 模块启用而生效；升级时旧 `enforce_ip` 偏好不再使用。需要恢复系统地址分配时，在 LSPosed 中禁用模块并重启。不要同时启用原版 SoftApHelper 的相同 Hook。

### 固定 IP 原理与排查

- Android 10：替换 `android.net.ip.IpServer.getRandomWifiIPv4Address()`。
- Android 11+：Hook `IpServer.requestIpv4Address` 的 `LinkAddress` 返回值方法，适配无参数、布尔参数及 Android 14+ 的多参数形式；仅在 `mInterfaceType == 0`（Wi-Fi）且调用栈包含 `IpServer.configureIPv4` 时替换。
- 保留上游冲突检查：Android 11 检查上游/下游网段；Android 12+ 使用 `getConflictPrefix`。冲突时保留系统分配。无法识别的类型、异常及不完整的旧版冲突接口也保留系统行为。
- Android 15+ 如果已移除 `mPrivateAddressCoordinator` 字段，沿用上游不检查冲突的兼容分支并记录日志。这是兼容逻辑，不是这些系统版本的真机验证结论。
- LSPosed 日志中搜索 `[PhoneRouter/SoftApHelper]`：`Installed ... hook(s)` 表示入口安装；`Wi-Fi gateway selected` 表示地址替换执行；`Keep system address` 表示回退。某个推荐进程缺少 `IpServer` 时会记录日志，需查看实际承载 Tethering 的进程是否成功 Hook。
- 上游说明部分 Android 14 ROM 需要在 LSPosed 中对 Tethering 执行“重新优化”后重启。目标手机是否需要，以实际日志和地址分配结果为准。
- 当前管理地址及热点状态检测仍以固定网关为前提；模块未生效或冲突回退时，App 可能显示热点关闭，固定管理地址也可能不可达。此时先检查系统热点状态、实际接口地址与 LSPosed 日志，不能只凭 App 状态断定热点没有运行。

> 建议在 App 内修改默认 Web 密码和端口。MIUI/HyperOS 请把本应用加入「自启动」白名单并关闭电池优化，提升可靠性。

## Web API（均需先 `POST /api/login` 取 token，后续带 `X-Auth: <token>`）

| 方法 | 路径 | 作用 |
|---|---|---|
| POST | /api/login | `{"password":".."}` → `{ok,token}` |
| GET  | /api/status | 状态/流量/时长/网关/客户端数/配置 |
| GET  | /api/config | 读 SoftAp 配置 |
| POST | /api/config | `{ssid,passphrase,security,band,hidden,maxClients}` 改配置并应用 |
| POST | /api/hotspot | `{"enabled":true/false}` 开关热点 |
| GET  | /api/clients | 在线设备列表（含黑名单离线项） |
| POST | /api/clients/block | `{"mac":"..","block":true}` 拉黑/解除 |
| POST | /api/reboot-hotspot | 重启热点 |
| GET  | /api/data-usage | 已用/剩余/套餐/周期 |
| POST | /api/data-usage/config | `{quotaMb,resetDay}` 设置套餐 |
| POST | /api/data-usage/refresh | 立即刷新已用流量 |
| GET/POST | /api/speed-limit | 读/设限速 `{downKbps,upKbps}` |
| GET  | /api/schedules | 定时任务列表 |
| POST | /api/schedules | 新增 `{hour,minute,days,action,enabled}` |
| POST | /api/schedules/delete·toggle | 删除 / 启停 `{id,...}` |
| GET  | /api/sms/subs | SIM 卡列表 |
| GET  | /api/sms?box=inbox&limit=50 | 短信列表 |
| POST | /api/sms/send | `{subId,to,body}` 发送 |
| GET  | /api/files?path=/ | 列目录 |
| GET  | /api/files/download?path=&token= | 下载 |
| POST | /api/files/upload | 上传(multipart: path,file) |
| POST | /api/files/mkdir·delete·rename | 新建/删除/改名 |

`security`: 0=开放 1=WPA2 2=WPA2/WPA3 3=WPA3；`band`: 1=2.4G 2=5G；`days`: 1=周一..7=周日(空=每天)；限速单位 kbit/s(0=不限)。

## 说明与注意

- **改配置 / 拉黑会重启热点**以生效，期间已连设备会短暂断开（自动重连）。
- **固定 IP**：由本 APK 内置的 LSPosed Hook 在系统地址分配阶段实现，无需另装 SoftApHelper；作用域和重启要求见上文。网段冲突、ROM 差异或模块未加载时不会用 root 强行补设地址。
- **Web 服务绑定全部网卡**的指定端口，访问范围取决于手机实际路由与防火墙，并未在代码中限制为热点网段。管理端使用 HTTP，请仅用于可信网络并修改默认密码。
- 若 App 显示「Root 不可用」，到超级用户管理器确认已对本应用授权。

## 许可

固定 IP 代码移植自 [SoftApHelper](https://github.com/XhyEax/SoftApHelper)，基于提交 `a16231df89531d094d0f9e0e73d0259807c4cee0`，采用 MIT 许可。完整版权和许可随 APK 放在 `assets/licenses/SoftApHelper-LICENSE.txt`，移植范围见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。网页管理参考 [UFI-TOOLS](https://github.com/kanoqwq/UFI-TOOLS) 的实现思路。
