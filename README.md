# 玉简（YuJian）· Android TV 悬浮通知与 Sendspin 多房间音乐终端

> **一简传音，灵讯常驻；多房同频，大屏随心。**
>
> **玉简（YuJian / TVNotify）** 是一款专为 **Android TV / 智能电视** 打造的轻量级常驻服务应用：一边作为局域网 HTTP 悬浮通知横幅接收器（深度适配 Home Assistant、NAS 与各类自动化脚本），一边内置 **Sendspin 多房间同步音乐播放器** 与 **自适应单页 Web 控制台**，让大屏在息屏观影之余也能优雅承接家庭消息与高品质同步音乐。

---

## ✨ 核心特性（v1.9.0）

| 模块 | 能力亮点 |
| --- | --- |
| **🖥️ 3 页签焦点即切 TV 主页** | 采用「**总览与互联**」「**演练与调控**」「**系统与运维**」三大页签对称双栏布局，遥控器焦点移上即刻切换，返回键先回顶栏，告别单屏多排纵深翻找。 |
| **🎨 9 大美学主题 + 原生 DSP 音效** | 内置「修仙玉简」「tvOS 极简」「大唐鎏金」「赛博霓虹」「黑神话·暗金」「极客终端」「江南宣纸」「主机奖杯」「🧱 方块世界」，支持纯代码 44.1kHz WAV 合成专属提示音与 Canvas 矢量/像素自绘图标。 |
| **🎵 Sendspin 多房间同步音乐** | 支持 mDNS（`_sendspin._tcp.`）自动发现 Music Assistant 或手动指定 WebSocket 地址；**默认开启自动同步**（Kalman 时钟校准 + `AudioTimestamp` 硬件反馈 + 平滑重采样拼接），亦可切换手动固定补偿（0–5000 ms）。 |
| **💿 黑胶转盘与 LRC 同步歌词** | 配套独立全屏电视播放页：提取封面环境光晕、转动黑胶唱片，支持内嵌歌词 / 磁盘缓存 / `lrclib.net` 在线时间轴同步歌词滚动，遥控器与媒体按键直达调控。 |
| **📱 手机自适应单页 Web 控制台** | 浏览器访问 `http://电视IP:8791/{token}` 即开，保留顺畅单页长滚动与全 9 套主题换肤；针对窄屏手机重构 `minmax(0, 1fr)` / `3×2` 遥控面板 / 防遮挡 cURL 栏，彻底告别挤压溢出。 |
| **🖼️ 多媒体横幅与语音播报** | 支持 6 大显示方位（顶/底/四角画中画）、三级动态着色、图文、静态快照轮询、MJPEG 实时视频流、排队队列、同 `id` 原地刷新与原生中文 TTS 语音播报。 |
| **🛡️ 局域网离线常驻** | 零外部 CDN / 零云端依赖，开机自启 + 前台保活通知 + Wi-Fi 高性能低延迟锁，并支持 GitHub Release 一键检测 OTA 升级。 |

---

## 🎨 9 大主题与专属提示音一览

支持全局切换，也支持单次推送通过 `"theme"` 参数临时覆盖（亦可通过 `"mc"`、`"cyber"`、`"ink"` 等别名传参）：

| 主题 ID | 显示名称 | 视觉风格与字体 | 横幅特征 | 专属提示音 |
| --- | --- | --- | --- | --- |
| `xiuxian` | 🔮 修仙玉简（默认） | 玄青羊脂灵玉 · 衬线体 | 20dp 圆角 · 青玉灵纹图标 | 内置空灵玉磬音 |
| `default` | 🍎 tvOS 极简 | 苹果暗黑毛玻璃 · 无衬线 | 15dp 圆角 · 极简纯白/彩框 | 内置柔和三和弦 |
| `palace` | 🏮 大唐鎏金 | 玄黑朱漆赤金 · 衬线体 | 20dp 圆角 · 鎏金卷轴图标 | 合成宫廷青铜编钟 |
| `cyberpunk` | ⚡ 赛博霓虹 | 夜之城紫黑霓虹 · 等宽体 | 8dp 硬朗切角 · 霓虹六边形 | 合成科幻 HUD 脉冲扫频 |
| `wukong` | 🐵 黑神话·暗金 | 焦墨石板暗金 · 衬线体 | 16dp 圆角 · 金箍环图标 | 合成古刹金钵低鸣 |
| `matrix` | 💻 极客终端 | 纯黑 CRT 荧光绿 · 等宽体 | 8dp 切角 · 终端字符图标 | 合成复古双脉冲蜂鸣 |
| `ink_light` | 🍵 江南宣纸 | 仿古米白宣纸 · 松烟浓墨 | 20dp 圆角 · 浅色日间横幅 | 合成空谷清竹水滴音 |
| `gamer` | 🏆 主机奖杯 | PS5 深蓝白金 · 无衬线 | 22dp 胶囊圆角 · 黄金奖杯 | 合成解锁奖杯四连琶音 |
| `minecraft` | 🧱 方块世界 | 深石板翡翠草绿 · 等宽体 | **0dp 纯直角方块** · 8×8 像素草方块 | 合成 8-bit 经验球三连音 |

> 扩展主题的提示音由 [`ThemeSoundSynth.kt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/ThemeSoundSynth.kt) 在首次使用时纯数学合成 44.1kHz 16-bit PCM WAV 并缓存在应用 `cacheDir`，零额外安装包体积开销。

---

## 🎵 Sendspin 多房间同步音乐

1. **接入方式**：
   - **自动发现**：默认通过 Android NSD 自动发现局域网内 `_sendspin._tcp.` 服务并连接至 Music Assistant。
   - **手动指定**：在 Web 控制台 Sendspin 卡片或电视播放器「设置 -> 连接 Music Assistant」中填写 `ws://<host>:8927/sendspin`（留空则恢复自动发现）。
2. **输出同步机制（默认开启自动同步）**：
   - 采用 Sendspin 协议内置的 Kalman 滤波时钟同步，结合 Android `AudioTrack` / `AudioTimestamp` 硬件播放位置反馈，实时计算并利用平滑重采样 / 帧插拔调节消除多房间播音偏差；
   - 如个别电视 HDMI/回音壁链路存在固定外部延迟，可在 Web 控制台将「同步模式」切换为 **手动固定补偿**（支持 `0 ~ 5000 ms` 整数毫秒，切换回自动同步时保留手动输入值供随时恢复）。
3. **全屏播放器交互**：
   - 当开始播放时可配置「播放时自动打开电视音乐页」，亦可随时从主页「总览与互联」快捷卡片或 Web 控制台远程唤起 [`SendspinActivity`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/SendspinActivity.kt)。
   - 支持遥控器方向键切换焦点（上一首 / 播放暂停 / 下一首 / 进度条快进退 / 音量弹窗 / 连接与自动弹出设置），并响应电视遥控器物理媒体键（Play / Pause / Play-Pause / Next / Previous / Stop）。

---

## 📱 手机端 Web 控制台与二维码互联

在电视端主页首页打开**「总览与互联」**：
1. 扫码切换 **「Web 控制台（推荐手机操作）」**：手机浏览器直接打开 `http://<电视IP>:8791/{token}`，即可在单页内完成发信调试、位置/静音/9 主题切换、Sendspin 音乐与同步模式调节、历史消息重演以及实时生成 cURL 命令。
2. 扫码切换 **「接口接入二维码」**：直接复制接入地址 `http://<电视IP>:8791/{token}/notify` 给 Home Assistant 或其他自动化脚本使用。

---

## 📡 HTTP API 接口文档（默认端口：`8791`）

### 1. 发送悬浮通知：`POST /{token}/notify`

```bash
curl -X POST "http://192.168.1.100:8791/<你的Token>/notify" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "💎 已解锁成就：洗衣完成",
    "text": "阳台滚筒洗衣机已完成烘干流程，请及时晾晒。",
    "level": "info",
    "position": "top-right",
    "theme": "minecraft",
    "duration": 8,
    "tts": true
  }'
```

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | :---: | --- | --- |
| `title` | string | 否 | 主题默认标题 | 横幅主标题（`title`/`text`/`image`/`image_base64`/`video` 至少一项非空） |
| `text` | string | 否 | `""` | 横幅正文内容 |
| `level` | string | 否 | `"info"` | `info` / `warn` / `error` |
| `duration` | int | 否 | `5` | 停留秒数（限制在 `1 ~ 30`） |
| `sticky` | boolean | 否 | `false` | 是否常驻直至下次替换、达到 `maxDuration` 或调用 `/dismiss` |
| `maxDuration` | int | 否 | `300` | 常驻模式下兜底自动关闭最大秒数（默认 300 秒） |
| `position` | string | 否 | 跟随全局 | `top`、`bottom`、`top-left`、`top-right`、`bottom-left`、`bottom-right` |
| `theme` | string | 否 | 跟随全局 | 单次主题覆盖：`xiuxian`、`default`、`palace`、`cyberpunk`、`wukong`、`matrix`、`ink_light`、`gamer`、`minecraft`（兼容常用别名） |
| `sound` | boolean | 否 | `true` | 是否播放主题提示音 |
| `tts` | boolean / string | 否 | `false` | `true` 播报横幅标题与正文，或传入自定义播报文本 |
| `queue` | boolean | 否 | `false` | `true` 排队依次展示；`false` 立即接管替换（相同 `id` 原地刷新除外） |
| `id` | string | 否 | 自动时间戳 | 同一 `id` 在屏幕展示中触发无闪烁原地更新（倒计时、进度、摄像头状态） |
| `image` / `image_base64` | string | 否 | 空 | 右侧展示大图（HTTP URL 或 Base64） |
| `image_interval` | int | 否 | `0` | 定时轮询 `image` 快照刷新间隔（`1~10` 按秒计，`>10` 按毫秒计） |
| `video` | string | 否 | 空 | MJPEG 视频流地址（如门铃实时画面或内置 `/stream`） |

### 2. 关闭当前横幅：`POST /{token}/dismiss`

```bash
curl -X POST "http://192.168.1.100:8791/<你的Token>/dismiss"
```

### 3. 远程调控电视默认配置：`POST /{token}/config`

```bash
curl -X POST "http://192.168.1.100:8791/<你的Token>/config" \
  -H "Content-Type: application/json" \
  -d '{"theme": "minecraft", "position": "top-right", "muted": false}'
```

### 4. 获取最近通知历史：`GET /{token}/history`

返回最近 `20` 条推送记录 JSON 数组（供 Web 控制台与外部排查联动）。

### 5. Sendspin 状态查询与远程控制：`GET / POST /{token}/sendspin`

- **`GET /{token}/sendspin`**：返回连接与播放状态（`state`、`connected`、`playing`、`server`、`server_url`、`client_name`、`title`、`artist`、`album`、`progress_ms`、`duration_ms`、`volume`、`muted`、`sync_mode`、`automatic_sync`、`manual_output_delay_ms`、`output_delay_ms`、`clock_ready`、`clock_uncertainty_ms`、`sync_state`、`position_source`、`sync_error_ms`、`resync_count`、`underrun_count` 等）。
- **`POST /{token}/sendspin`**：按 `action` 分发操作：
  - **配置修改（`action: "config"`）**：支持 `enabled`（boolean）、`auto_popup`（boolean）、`server_url`（string）、`client_name`（string）、`automatic_sync`（boolean）与 `output_delay_ms`（0–5000 整数毫秒）。
  - **界面控制**：`{"action": "open_ui"}` 打开电视音乐页、`{"action": "close_ui"}` 关闭电视音乐页。
  - **播控指令（`action: "command"` 或省略）**：传入 `command`（如 `play_pause`、`next`、`previous`、`volume_up`、`volume_down`、`mute`、或带 `volume` / `muted` / `position_ms`）。

### 6. 服务健康探针与测试流：`GET /health`、`GET /{token}/stream`

```json
{
  "ok": true,
  "version": "1.9.0",
  "uptime": 3600,
  "port": 8791,
  "banner": "idle",
  "muted": false,
  "position": "top",
  "theme": "xiuxian"
}
```

---

## 🏠 Home Assistant 接入示例

在 Home Assistant `configuration.yaml` 中添加 `rest_command`：

```yaml
rest_command:
  tv_notify:
    url: "http://192.168.1.100:8791/<你的Token>/notify"
    method: POST
    headers:
      Content-Type: application/json
    payload: >-
      {
        "title": "{{ title | default('家庭提醒') }}",
        "text": "{{ text | default('') }}",
        "level": "{{ level | default('info') }}",
        "position": "{{ position | default('') }}",
        "theme": "{{ theme | default('') }}",
        "duration": {{ duration | default(6) }},
        "tts": {{ tts | default(false) | tojson }},
        "image": "{{ image | default('') }}"
      }
```

---

## 🔧 安装部署与保活建议

1. 前往 [Releases](../../releases) 下载最新 `YuJian-release.apk` 并安装至 Android TV / 电视盒子（最低兼容 Android 6.0 / API 23）。
2. 启动后依据电视弹窗授予 **「显示在其他应用上层（SYSTEM_ALERT_WINDOW）」** 悬浮窗权限。
3. **后台常驻保活建议（部分国产电视系统杀后台较严）**：
   - 将玉简加入系统自启动白名单与电池无限制名单；
   - 如配合第三方桌面（如 Aerial）或在电脑通过 ADB 授权保活，可执行：

```bash
adb shell appops set com.zqc.tvnotify RUN_ANY_IN_BACKGROUND allow
adb shell dumpsys deviceidle whitelist +com.zqc.tvnotify
```

---

## 📂 项目目录结构

```text
玉简/
├── .github/workflows/build.yml        # GitHub Actions 自动化单测、Lint 与 Release 打包
├── third_party/sendspin-jvm/          # 内置 Kotlin Sendspin 协议模块 (:sendspin-protocol)
└── app/
    ├── build.gradle.kts               # minSdk 23, targetSdk 34, Java 17
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/licenses/       # 第三方开源许可声明
        │   ├── res/                   # 图标、chord/chord_xiuxian 默认音频与采样图
        │   └── java/com/zqc/tvnotify/
        │       ├── MainActivity.kt            # 顶部 3 页签焦点即切 TV 主界面与双模式二维码
        │       ├── Theme.kt                   # 9 大主题枚举、配色/字体/圆角/双语文案系统
        │       ├── ThemeSoundSynth.kt         # 7 款扩展主题纯代码 44.1kHz PCM WAV 声学合成器
        │       ├── BannerController.kt        # WindowManager 悬浮窗、排队/UPSERT 与矢量/方块图标
        │       ├── WebConsoleHtml.kt          # 手机自适应单页 Web 控制台与 9 主题实时同步
        │       ├── NotifyService.kt           # 常驻前台服务、开机自启与 HTTP/mDNS 托管
        │       ├── NotifyHttpServer.kt        # NanoHTTPD REST 接口 (/notify, /sendspin, /config...)
        │       ├── NotifyHistory.kt           # 最近 20 条通知持久化历史
        │       ├── Prefs.kt                   # Token、静音、主题、方位及 Sendspin 配置持久化
        │       ├── SendspinActivity.kt        # 全屏电视音乐播放页（封面光晕/歌词/媒体键）
        │       ├── SendspinPlayerViews.kt     # 自绘黑胶唱片转盘、歌词滚动视图与焦点控件
        │       ├── SendspinRuntime.kt         # Sendspin 协议会话、时钟同步与播放状态管理
        │       ├── SendspinPcmPump.kt         # 实时音频重采样与平滑同步输出泵
        │       ├── AndroidAudioPlayer.kt      # AudioTrack 硬件时间戳位置采样与播放封装
        │       ├── AndroidSendspinNsdBrowser.kt # 局域网 _sendspin._tcp mDNS 服务发现
        │       ├── LyricsFetcher.kt           # LRC 解析器、磁盘缓存与 lrclib.net 异步歌词检索
        │       ├── TrackProgressTracker.kt    # 基于硬件时钟的播放进度插值跟踪
        │       ├── SendspinPrefsStore.kt      # Sendspin 同步偏好与 URL 存储助手
        │       ├── audio/                     # PCM 帧时间轴、线性重采样、拼接平滑与同步算法
        │       ├── AppUpdater.kt              # GitHub Release OTA 检查与 FileProvider 安装
        │       ├── MdnsAdvertiser.kt          # 通知服务局域网 mDNS 广播
        │       └── SafeNetworkHelper.kt / NetUtils.kt / BootReceiver.kt / ApkFileProvider.kt
        └── test/
            ├── java/com/zqc/tvnotify/         # JUnit 单测（主题/Tab、LRC、Sendspin 同步算法与音泵）
            └── js/sendspin-console.test.cjs   # Node.js 控制台前端交互与手机响应式校验测试
```

---

## 🛠️ 本地构建与验证

- **Android JVM 单元测试 & 构建**：

```bash
gradle :sendspin-protocol:test :app:testDebugUnitTest
gradle assembleDebug lintDebug
```

- **Web 控制台前端 & 手机响应式测试**：

```bash
node --test app/src/test/js/sendspin-console.test.cjs
```

---

## 📄 开源许可与致谢

- Sendspin 同步音频协议模块基于 `third_party/sendspin-jvm` 集成（许可见 [`app/src/main/assets/licenses/sendspin-jvm-LICENSE.txt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/assets/licenses/sendspin-jvm-LICENSE.txt)）。
- 本项目使用 [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) 提供极轻量化嵌入式 HTTP 服务，使用 [ZXing](https://github.com/zxing/zxing) 生成配网二维码，歌词在线回源支持 [lrclib.net](https://lrclib.net)。
