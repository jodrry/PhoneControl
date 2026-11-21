# PhoneControl：局域网手机控制手机

一个可编译的 Android Studio 多模块工程：两台 Android 手机连接同一局域网后，控制端实时查看被控端屏幕，并通过 WebSocket 发送归一化触摸坐标和返回 / Home 指令。全程免 Root、本地直连，不经过公网服务器。

- 被控端：`agent`，应用名「手机被控端」
- 控制端：`controller`，应用名「手机控制端」
- 语言：Kotlin
- `minSdk 26` / `targetSdk 34` / `compileSdk 34`
- 视频：MediaProjection + MediaCodec H.264
- 注入：AccessibilityService + `dispatchGesture`
- 通信：Java-WebSocket，文本 JSON + 二进制视频帧双通道

---

## 1. 项目结构

```text
PhoneControl/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/wrapper/
├── .github/workflows/build.yml
├── docs/PROTOCOL.md
├── tools/
│   ├── validate_project.py
│   └── bootstrap_build_env.py
│
├── agent/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/phonecontrol/agent/
│       │   ├── MainActivity.kt                 # 权限引导 + 状态页
│       │   ├── AgentService.kt                 # 前台 Service，持有全部运行时
│       │   ├── ScreenCaptureEngine.kt          # MediaProjection + MediaCodec 编码
│       │   ├── AgentWebSocketServer.kt         # WebSocket 服务端
│       │   ├── RemoteAccessibilityService.kt   # 无障碍服务单例
│       │   ├── GestureInjector.kt              # dispatchGesture 封装
│       │   ├── TouchGestureSession.kt          # down/move/up -> tap/longPress/swipe
│       │   ├── NsdAdvertiser.kt                # mDNS 广播 _phonecontrol._tcp
│       │   ├── NetworkUtils.kt                 # 局域网 IPv4
│       │   ├── AgentState.kt                   # StateFlow 状态
│       │   └── WireProtocol.kt                 # 二进制帧协议
│       └── res/
│           ├── layout/activity_agent.xml
│           ├── xml/accessibility_service_config.xml
│           ├── xml/network_security_config.xml
│           └── values/...
│
└── controller/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/phonecontrol/controller/
        │   ├── MainActivity.kt                 # 连接、画面、触摸、状态、延迟
        │   ├── ControllerWebSocketClient.kt    # WebSocket 客户端
        │   ├── VideoDecoder.kt                 # MediaCodec 解码到 Surface
        │   ├── AspectRatioFrameLayout.kt       # 保持视频比例，防坐标错位
        │   ├── NsdDiscovery.kt                 # mDNS 发现
        │   └── WireProtocol.kt
        └── res/layout/activity_controller.xml
```

---

## 2. 核心类设计

### 被控端 agent

| 类 | 职责 |
| --- | --- |
| `MainActivity` | 首次引导、通知权限、无障碍跳转、屏幕捕获授权、启动/停止服务、展示 IP/端口/连接数 |
| `AgentService` | 前台 Service（`mediaProjection` 类型），创建 `MediaProjection`、编码器、WebSocket 服务端、mDNS、通知 |
| `ScreenCaptureEngine` | `MediaProjection.createVirtualDisplay` 的输出 Surface 接到 `MediaCodec` 编码器，输出 H.264 并封装为二进制包 |
| `AgentWebSocketServer` | 监听 8765 端口；处理 hello/ping/touch/action/requestSync；广播配置帧和视频帧 |
| `RemoteAccessibilityService` | 用户手动开启的无障碍服务，暴露进程内单例 |
| `GestureInjector` | 将 0~1 坐标换算到真实屏幕像素，调用 `dispatchGesture` / `performGlobalAction` |
| `TouchGestureSession` | 缓存一次 down→move*→up，在抬手时决定 tap/longPress/swipe |
| `NsdAdvertiser` | 注册 `_phonecontrol._tcp.` 服务，便于控制端自动发现 |
| `AgentState` | 进程内 `StateFlow`，驱动 Activity 和通知 |

### 控制端 controller

| 类 | 职责 |
| --- | --- |
| `MainActivity` | 手动 IP/端口、mDNS 扫描列表、连接/断开、触摸归一化、返回/Home、延迟显示 |
| `ControllerWebSocketClient` | `ws://host:port/` 客户端，文本 JSON 和二进制帧回调 |
| `VideoDecoder` | 从配置包构建 SPS/PPS，`MediaCodec` 解码并渲染到 SurfaceView 的 Surface |
| `AspectRatioFrameLayout` | 按远端屏幕比例调整画面区域，SurfaceView 不拉伸，触摸映射 1:1 |
| `NsdDiscovery` | 发现并逐个 resolve 局域网被控端 |

---

## 3. 调用关系

```text
被控端：
MainActivity
  └─ 屏幕捕获授权 (MediaProjectionManager)
       └─ startForegroundService(AgentService)
            ├─ startForeground(type=mediaProjection)
            ├─ getMediaProjection()
            ├─ AgentWebSocketServer.start()
            ├─ ScreenCaptureEngine.start()
            │    ├─ MediaCodec encoder (Surface)
            │    └─ MediaProjection.createVirtualDisplay(inputSurface)
            │         └─ onFrame -> AgentWebSocketServer.broadcastFrame()
            └─ NsdAdvertiser.start()

控制端 JSON touch/action
  └─ AgentWebSocketServer.onMessage
       ├─ touch -> TouchGestureSession -> GestureInjector -> RemoteAccessibilityService.dispatchGesture
       └─ action -> GestureInjector -> performGlobalAction

控制端：
SurfaceView
  └─ onTouch -> 归一化 x/y -> ControllerWebSocketClient.sendTouch(JSON)

Agent binary frame
  └─ ControllerWebSocketClient.onMessage(ByteBuffer)
       └─ VideoDecoder.submit -> MediaCodec decoder -> SurfaceView Surface
```

### 关键顺序（Android 14 合规）

1. Activity 先通过 `createScreenCaptureIntent()` 拿到用户授权结果。
2. `startForegroundService()` 启动 `AgentService`。
3. Service **先** `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`。
4. **再**调用 `MediaProjectionManager.getMediaProjection()`。
5. 注册 `MediaProjection.Callback` 后创建 `VirtualDisplay`。
6. 停止时先释放编码器/虚拟显示，再 `projection.stop()`，最后移除前台通知。

---

## 4. 通信协议摘要

完整定义见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

- WebSocket 地址：`ws://<被控端IP>:8765/`
- 文本通道：JSON
- 二进制通道：18 字节头 + H.264 数据

文本消息：

```json
{"type":"hello","role":"agent","protocol":1,"name":"Xiaomi 14","width":1080,"height":2400,"port":8765}
{"type":"touch","phase":"down","id":0,"x":0.4321,"y":0.6180}
{"type":"touch","phase":"move","id":0,"x":0.4402,"y":0.6201}
{"type":"touch","phase":"up","id":0,"x":0.4410,"y":0.6210}
{"type":"action","action":"back"}
{"type":"requestSync"}
{"type":"ping","t":123456789}
{"type":"pong","t":123456789}
```

二进制头（大端）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| magic | int32 | `0x50435631`（"PCV1"） |
| type | uint8 | 1=配置帧，2=视频帧 |
| flags | uint8 | bit0=关键帧 |
| width | uint16 | 编码尺寸 |
| height | uint16 | 编码尺寸 |
| ptsUs | int64 | 编码器 presentationTimeUs |

配置 payload：`uint8 NAL 数`，随后每个 NAL 为 `int32 长度 + 原始 NAL（不含 start code）`。控制端把 SPS 放入 `csd-0`，PPS 放入 `csd-1`，并补 `00 00 00 01` 起始码。

---

## 5. 坐标换算

控制端始终发送：

```text
x = touchX / surfaceWidth      // 0..1
y = touchY / surfaceHeight     // 0..1
```

被控端收到后：

```text
pixelX = x * realScreenWidth
pixelY = y * realScreenHeight
```

被控端的 `GestureInjector` 使用 `WindowManager.defaultDisplay.getRealSize()` 取得真实屏幕尺寸（包含系统栏区域），所以被控端采集分辨率被缩放到 1280 长边也不会影响坐标。

`AspectRatioFrameLayout` 会让 SurfaceView 自身保持视频宽高比，避免黑边区域被误算成触摸区域。

---

## 6. 构建环境

- Android Studio Iguana 或更高（推荐）
- JDK 17
- Gradle 8.7（`gradle-wrapper.properties` 已固定）
- Android SDK Platform 34、Build-Tools 34.0.0

命令行：

```bash
cd PhoneControl
./gradlew assembleDebug
```

APK：

```text
agent/build/outputs/apk/debug/agent-debug.apk
controller/build/outputs/apk/debug/controller-debug.apk
```

GitHub Actions 会在 push 后自动构建并上传 `PhoneControl-debug-apks` artifact，见 `.github/workflows/build.yml`。

---

## 7. 两台手机运行步骤

### 7.1 安装

分别安装：

- `agent-debug.apk` → 被控端手机
- `controller-debug.apk` → 控制端手机

两台手机连接同一个 Wi-Fi。路由器必须关闭「AP 隔离 / 客户端隔离」。部分公共 Wi-Fi 会禁止设备互访，这种情况下即使发现设备也无法连接。

### 7.2 被控端首次配置

1. 打开「手机被控端」。
2. 点「授予通知权限」（Android 13+）。拒绝通知权限通常仍能录屏，但前台通知可能不显示。
3. 点「打开无障碍设置」，进入系统设置 → 无障碍 → 已下载的服务 / 已安装的服务 → 「手机被控端 / 远程控制手势服务」→ 开启。
4. 返回应用，确认状态卡中「无障碍服务」显示「已开启」。
5. 点「开始共享」，系统弹出「开始录制或投放」→ 选择「立即开始 / 允许」。
6. 授权后，状态显示「屏幕共享中」，并显示本机 IP、端口（默认 8765）、采集分辨率。
7. 保持该页面或退到后台均可；前台服务会维持屏幕捕获和 WebSocket。通知栏可停止共享。

> Android 14 每次开始新的屏幕共享都需要重新确认系统录屏弹窗，这是系统限制，无法绕过。

### 7.3 控制端

1. 打开「手机控制端」。
2. 等待「局域网设备」下拉框出现被控端，或点「扫描局域网」。
3. 选择设备（自动填入 IP/端口），或手动输入被控端显示的 IP 和 8765。
4. 点「连接」。连接成功显示「已连接」、远端分辨率和延迟。
5. 在黑色画面区域触摸：
   - 快速点击 → 远程点击
   - 按住约 0.65 秒后松开 → 远程长按
   - 拖动后松开 → 远程滑动
6. 使用「返回」「Home」按钮执行系统导航。
7. 点「断开」结束连接。

### 7.4 权限清单

被控端：

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_MULTICAST_STATE`
- `POST_NOTIFICATIONS`（运行时，Android 13+）
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`
- `WAKE_LOCK`
- 无障碍服务（系统特殊权限，用户手动开启）
- 屏幕捕获（每次运行时授权）

控制端：

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_MULTICAST_STATE`

---

## 8. 常见问题

**找不到设备 / 连接超时**

- 确认两台手机在同一 Wi-Fi。
- 关闭路由器的 AP 隔离、访客网络隔离。
- 关闭手机上的 VPN / 代理 / 防火墙。
- mDNS 被路由器或系统拦截时，直接手动输入被控端显示的 IP 和端口。
- 部分 ColorOS / MIUI / HyperOS 需要允许应用后台运行、关闭电池优化。

**能看画面但点击没反应**

- 被控端无障碍服务未开启，或系统在升级后自动关闭。重新进入无障碍设置开启。
- 部分系统会限制无障碍服务注入；可在系统设置中允许「后台弹出界面」「后台运行」。

**停止后不能直接恢复**

- MediaProjection 授权令牌在 `stop()` 后失效；重新点「开始共享」并再次确认系统弹窗。

**旋转屏幕后画面比例异常**

- 当前版本在开始共享时固定采集尺寸。旋转后请停止共享并重新开始，或保持被控端竖屏。

**延迟高**

- 默认采集长边不超过 1280、30fps、码率随分辨率自适应（1.5~8 Mbps）。
- 5 GHz Wi-Fi、靠近路由器可降低延迟。
- 控制端解码队列有上限；丢帧时会自动请求关键帧。

---

## 9. 已知限制与安全说明

- 本工程未实现多指手势、音频转发、剪贴板、文件传输。
- 只支持单指触摸。多个控制端同时连接时，触摸状态机是共享的，建议只让一台手机控制。
- WebSocket 是明文 `ws://`，**没有鉴权**。请只在可信局域网使用，不要把 8765 端口暴露到公网。生产环境应增加配对码 / TLS / 连接白名单。
- 屏幕内容只发给已连接的局域网控制端；没有公网中转服务器。
- 不同 OEM 对 MediaProjection、后台前台服务、无障碍注入的限制不同，需要在目标机型上实测。
- 本项目不包含 Root 方案，也不使用 ADB 注入。

---

## 10. 自检

仓库带有轻量资源校验脚本：

```bash
python tools/validate_project.py
```

检查 XML 可解析、资源引用存在、ViewBinding ID 匹配。最终以 `./gradlew assembleDebug` 的编译结果为准。
