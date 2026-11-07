# 玉简（YuJian）内置轻量化 Sendspin 音乐与歌词客户端设计规格书

- **日期**：2026-11-07
- **目标设备**：小米电视 / 红米电视（Android 6.0 Marshmallow，`minSdk = 23`）及所有 Android TV 设备
- **集成形态**：直接集成于「玉简」（`com.zqc.tvnotify`）工程内，复用常驻前台服务 [`NotifyService`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/NotifyService.kt)、自研零依赖 [`MdnsAdvertiser`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/MdnsAdvertiser.kt)、Android 6.0 HTTPS 兼容工具 [`SafeNetworkHelper`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/SafeNetworkHelper.kt)、8 大视觉主题体系（[`Theme`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/Theme.kt)）与内置手机 Web 控制台。

---

## 1. 背景与目标

### 1.1 核心需求
在「玉简」电视端应用中内置一个极度轻量化的 **Sendspin**（Music Assistant 原生多房间同步音频与元数据协议）客户端：
1. **自动被 Music Assistant 发现并播放音乐**：支持局域网 mDNS 零配置自动发现（Server-Initiated `_sendspin._tcp.local.` 监听 `8928` 端口）及手动指定服务端连接（Client-Initiated `ws://ip:8927/sendspin`）。
2. **全屏专辑封面与大字同步滚动歌词界面（`SendspinActivity`）**：当收到音乐播放流或用户主动打开「灵音歌词台（Sendspin）」时，呈现电视 1080p 横屏双栏沉浸式播放器（左栏专辑封面、曲目信息、播放进度与遥控器播控；右栏大字高亮同步滚动歌词），并全面适配玉简 8 大视觉主题。
3. **多源同步歌词（LRC）支持**：优先解析曲目自带 LRC 歌词，若服务端未下发歌词则通过 [`SafeNetworkHelper.openConnection`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/SafeNetworkHelper.kt) 调用 `lrclib.net` API 自动异步检索匹配的精确时间轴歌词，并持久化缓存于本地 `cacheDir`。
4. **极致轻量化与 Android 6.0 兼容**：
   - 严守 `minSdk = 23`（Android 6.0）兼容底线。
   - **零新增第三方依赖**（不引入 OkHttp、Ktor、BouncyCastle、ExoPlayer、Glide、AndroidX 或任何 `.so` 原生解码库），APK 体积增量控制在 **35 KB 以内**。

### 1.2 非目标（Non-Goals）
- 不实现悬浮歌词条（用户已明确选择全屏封面 + 大字滚动歌词方案，避免遮挡电视影视字幕）。
- 不引入 FLAC/Opus 第三方解码库——通过向服务端声明 `player@v1_support` 仅支持 `pcm`（`48000Hz` / `44100Hz`、`16-bit`、`2` 声道立体声），由服务端（如 Music Assistant）完成无损解码并以 PCM 帧直推电视端 `AudioTrack`。
- 不实现 `client-stream`（麦克风音频回传）角色。

---

## 2. 方案选型（Approaches Considered）

在头脑风暴阶段对比了两种方案，用户已明确选择 **方案 A**：

| 维度 | 方案 A（已选定：纯原生 Kotlin 零依赖 + PCM 直通 `AudioTrack`） | 方案 B（引入 Java-WebSocket + 第三方 FLAC/Opus 解码器） |
| :--- | :--- | :--- |
| **APK 体积增量** | **~30 KB**（零新增依赖） | +1.5 MB ~ 4 MB |
| **Android 6.0 兼容性** | 100% 原生 API（`Socket`、`AudioTrack`、`javax.crypto`、`Canvas`） | 易受旧版 SSL/TLS 或 native ABI（`armeabi-v7a`）兼容问题影响 |
| **CPU 与内存开销** | 极低（服务端解码为 PCM，电视端直接写入 `AudioTrack` 环形缓冲） | 电视老旧 Cortex-A53 CPU 需承担实时解码 |
| **功能完整度** | 完整支持 `player@v1`、`metadata@v1`、`artwork@v1`、`controller@v1` 及 LRC 歌词 | 除多出压缩音频格式外与方案 A 相同 |

---

## 3. 整体架构与协议线级规格

### 3.1 模块划分与职责

在 `app/src/main/java/com/zqc/tvnotify/` 下新增以下内聚源码文件，并扩展现有组件：

#### 3.1.1 `SendspinCrypto.kt`（纯 Kotlin 加密与 Noise 握手层）
- **X25519 纯 Kotlin 实现**：实现 RFC 7748 `X25519` 标量乘法（`scalarMult` 与 `scalarMultBase`，基于 `LongArray(16)` 模 $2^{255}-19$ 运算，无 Java 11+ `XEC` 依赖，兼容 API 23）。
- **Noise 协议参数**：
  - 完整协议名字符串：`"Noise_KKpsk2_25519_AESGCM_SHA256"`（恰好 32 字节 ASCII；按 Noise 规范，当协议名长度 == 32 字节时，`h` 直接初始化为该 32 字节数组，`ck = h`）。
  - 角色：**Server 固定为 Initiator，Client 固定为 Responder**（无论 TCP 连接由哪一方发起）。
  - 预知静态密钥：双方在明文 `client/init` 与 `server/init` 中已交换 43 字符 Base64URL（无 `=` 填充）的 32 字节 Curve25519 静态公钥 `client_id`（`rs`）与 `server_id`（`s`）。
  - Prologue：精确拼接发送的 `client/init` 原始 UTF-8 字节与接收到的 `server/init` 原始 UTF-8 字节，`MixHash(prologue)`，随后按 `KKpsk2` 预消息模式依次执行 `MixHash(s_initiator = serverPubKey)` 与 `MixHash(s_responder = clientPubKey)`。
  - **Handshake Message 1（Server -> Client，模式 `e, es, ss`）**：
    1. 读取前 32 字节为服务端临时公钥 `re`，执行 `MixHash(re)`；
    2. `es`（Initiator ephemeral + Responder static）：`MixKey(X25519(clientStaticPriv, re))`；
    3. `ss`（Initiator static + Responder static）：`MixKey(X25519(clientStaticPriv, serverStaticPub))`；
    4. `DecryptAndHash(ciphertext)` 得到内部 JSON：`{"psk_id":"<43-char base64url>","psk_category":"sn"|"pr"|"lt"}`。
  - **PSK 查找与回退**：
    - `psk_id = base64url_nopad(SHA-256("sendspin-psk-id-v1" || PSK))`
    - 常量 `SENTINEL_PSK = SHA-256("sendspin-sentinel-psk-v1")`（对应 `psk_id = "GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo"`）。
    - 根据 `psk_category` 查找本地匹配的 `lt`（已保存配对记录，且校验 `server_id` 一致）或 `pr`（当前生效的 `pairing_psk`）；若未命中或类别为 `sn`，则回退使用 `SENTINEL_PSK`。
  - **Handshake Message 2（Client -> Server，模式 `e, ee, se, psk`）**：
    1. 生成客户端 32 字节临时密钥对 `(ePriv, ePub)`，写入 `ePub` 并 `MixHash(ePub)`；
    2. `ee`：`MixKey(X25519(ePriv, re))`；
    3. `se`（Initiator static + Responder ephemeral）：`MixKey(X25519(ePriv, serverStaticPub))`；
    4. `psk`：`MixKeyAndHash(selectedPsk)`；
    5. `EncryptAndHash("{}")` 附加在 `ePub` 后，Base64URL 编码后封装于 `{"type":"noise/handshake","payload":{"data":"..."}}` 发送。
  - **Split 进入传输层模式**：
    - `hkdf2(ck, ByteArray(0))` 派生 `(k1, k2)`：其中 `k1` 为 Initiator -> Responder（即 **Server -> Client 解密密钥**），`k2` 为 Responder -> Initiator（即 **Client -> Server 加密密钥**）。
    - AES-GCM Nonce：12 字节，前 4 字节为 `0x00000000`，后 8 字节为大端序无符号 64 位帧计数器 `n`（每加/解密一帧自增 1，AAD 为空字节数组）。
- **配对码（Pairing Token）**：
  - 随机生成 32 字节 `pairingPsk`，构造配对串 `SP:0${clientIdBase64Url}${pairingPskBase64Url}`（共 90 字符），可在电视端弹窗展示二维码或复制文本供 Music Assistant 扫码/输入配对。

#### 3.1.2 `SendspinModels.kt`（协议数据模型与时间同步滤波器）
- `SendspinTrackMetadata`：`timestamp`、`title`、`artist`、`albumArtist`、`album`、`artworkUrl`、`year`、`track`、`trackProgressMs`、`trackDurationMs`、`playbackSpeed`（1000 = 1.0x，0 = 暂停）。
- `SendspinControllerState`：`supportedCommands`、`volume`（0-100）、`muted`、`repeat`（`off`/`one`/`all`）、`shuffle`、`seekMaxMs`。
- `SendspinClockFilter`：
  - 基于 `client/time`（`{"type":"client/time","payload":{"client_transmitted":t1}}`）与 `server/time`（`{"type":"server/time","payload":{"client_transmitted":t1,"server_received":t2,"server_transmitted":t3}}`），在收到时刻 `t4 = localMicros()` 计算单次偏移测量值 $\theta = \frac{(t_2 - t_1) + (t_3 - t_4)}{2}$ 与往返延迟 $\delta = (t_4 - t_1) - (t_3 - t_2)$。
  - 采用最小延迟滑窗 + 指数平滑更新时钟偏移 `offsetUs`（满足 `serverTimeUs = localTimeUs + offsetUs`），提供 `serverToLocalUs(serverUs)` 与 `localToServerUs(localUs)` 双向转换，以及曲目实时进度推算：
    $$\text{progressMs} = \text{trackProgressMs} + \frac{(\text{nowServerUs} - \text{metadata.timestamp}) \times \text{playbackSpeed}}{1\,000\,000}$$

#### 3.1.3 `SendspinAudioPlayer.kt`（PCM 音频同步播放引擎）
- 使用 Android 原生 `AudioTrack`（`AudioManager.STREAM_MUSIC`、`AudioFormat.ENCODING_PCM_16BIT`、`AudioFormat.CHANNEL_OUT_STEREO`、`AudioTrack.MODE_STREAM`）。
- 支持动态采样率切换（`48000 Hz` / `44100 Hz`）与 `16-bit` 小端序 PCM（若收到单声道 `channels == 1` 自动复制为双声道立体声写入）。
- 维护按时间戳排序的音频块队列（上限 `1_048_576` 字节）：
  - 比较音频块目标本地播放时刻 `targetLocalUs` 与当前硬件播放头预计消费时刻 `playheadLocalUs`：
  - 若首包尚未到达播放时刻（超前 > 20ms），写入对应长度静音帧对齐；
  - 若音频块已显著滞后（滞后 > 45ms），丢弃过期帧追赶；
  - 正常范围内直接 `audioTrack.write(pcmBytes, 0, pcmBytes.size)`。
- 支持 `volume`（0-100 映射至 `AudioTrack.setVolume(gain)`）与 `muted` 独立状态。

#### 3.1.4 `SendspinManager.kt`（WebSocket 传输、线级报文格式与状态机）
- **线级 JSON 报文精确结构**：
  1. `client/init`：`{"type":"client/init","payload":{"version":1,"client_id":"<43字符>","suite":"25519_AESGCM_SHA256"}}`
  2. `client/hello`：
     ```json
     {
       "type": "client/hello",
       "payload": {
         "name": "玉简·MiTV",
         "unpaired_access": { "enabled": true },
         "supported_pair_methods": { "pairing_psk": { "locations": ["device"] } },
         "device_info": {
           "product_name": "YuJian TV",
           "manufacturer": "Xiaomi",
           "software_version": "1.5.0"
         },
         "supported_roles": ["player@v1", "metadata@v1", "artwork@v1", "controller@v1"],
         "player@v1_support": {
           "supported_formats": [
             { "codec": "pcm", "channels": 2, "sample_rate": 48000, "bit_depth": 16 },
             { "codec": "pcm", "channels": 2, "sample_rate": 44100, "bit_depth": 16 },
             { "codec": "pcm", "channels": 1, "sample_rate": 48000, "bit_depth": 16 },
             { "codec": "pcm", "channels": 1, "sample_rate": 44100, "bit_depth": 16 }
           ],
           "buffer_capacity": 1048576,
           "supported_commands": ["volume", "mute"]
         },
         "artwork@v1_support": {
           "channels": [0]
         }
       }
     }
     ```
     *注：若对接旧版明文服务端（`aiosendspin <= 4.x`），自动补充顶层 `"version": 1, "client_id": "..."` 字段并兼容 `artwork@v1_support.channels` 对象数组格式。*
  3. `client/state`：
     ```json
     {
       "type": "client/state",
       "payload": {
         "state": "synchronized",
         "player": { "volume": 100, "muted": false },
         "artwork": {
           "channels": [
             { "source": "album", "format": "jpeg", "width": 500, "height": 500 }
           ]
         }
       }
     }
     ```
  4. `stream/start` 解析：读取 `payload.player` 中的 `sample_rate`（默认 `48000`）、`channels`（默认 `2`）、`bit_depth`（默认 `16`）配置 `SendspinAudioPlayer`。
  5. `client/command`（播控指令上行）：`{"type":"client/command","payload":{"controller":{"command":"play"|"pause"|"stop"|"next"|"previous"|"volume"|"mute"|"switch","volume":80,"mute":false}}}`。
- **二进制消息 ID 与包头字节布局**：
  - **ID `0`（JSON 消息）**：`[0][utf8_json_bytes]`
  - **ID `1`（Fragmentation 分片消息）**：`[1][flags:uint8][inner_msg_id:uint8][fragment_bytes]`，其中 `flags` 的 bit 0 为 `1` 表示最后一个分片，为 `0` 表示后续还有分片；重组完成后按 `inner_msg_id` 分发。
  - **ID `4`（Player Audio 音频块）**：`[4][timestamp_us:int64_be(8字节)][pcm_bytes]`。
  - **ID `8`~`11`（Artwork 封面流，使用通道 0 即 ID `8`）**：
    - 新版规范分片传输：
      - Announce（14 字节）：`[8][flags=0x02][timestamp_us:int64_be(8字节)][total_size:uint32_be(4字节)]`
      - Part：`[8][flags=0x00][image_chunk_bytes]`（累加至 `total_size` 后用 `BitmapFactory` 解码）
      - Cancel（2 字节）：`[8][flags=0x01]`
    - 旧版单包兼容：若收到 ID `8` 且 `byte[1]` 大于 `0x02` 或后续直接为 JPEG/PNG 魔数（`byte[9]==0xFF && byte[10]==0xD8` 或 `0x89 0x50`），按 `[8][timestamp_us:8][image_bytes]` 直接解码。

#### 3.1.5 `LyricsFetcher.kt`（LRC 歌词解析器与多源异步检索器）
- **`LrcParser`**：解析标准 `[mm:ss.xx]` / `[mm:ss.xxx]` 时间轴标签、同行多时间戳标签、`[offset:+/-ms]` 偏移标签，输出升序排列的 `List<LrcLine(timeMs: Long, text: String)>`。
- **网络请求强制使用 [`SafeNetworkHelper.openConnection`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/SafeNetworkHelper.kt)**：
  - 针对 Android 6.0（API 23）系统根证书过期问题，所有对 `https://lrclib.net/api/get` 与 `https://lrclib.net/api/search` 的请求均通过 `SafeNetworkHelper.openConnection(url, connectTimeoutMs = 4500, readTimeoutMs = 4500)` 发起，确保在红米/小米 Android 6.0 电视上不会因 Let's Encrypt 证书报 `SSLHandshakeException`。
- **本地磁盘缓存**：缓存在 `context.cacheDir/sendspin_lyrics/` 下，最多保留 100 首。

#### 3.1.6 `SendspinActivity.kt`（1080p 全屏专辑封面与大字同步滚动歌词页）
- **双栏大屏布局（1080p 横屏）**：
  - **左栏（42% 宽度）**：顶部状态徽章、大尺寸圆角专辑封面（无封面时绘制主题风格黑胶灵盘图腾）、曲目名/歌手/专辑、实时进度条（`mm:ss / mm:ss`）、底部遥控器播控按钮排（`⏮ 上一曲`、`⏯ 播放/暂停`、`⏭ 下一曲`、`🔉 音量-`、`🔊 音量+`、`🎨 主题`、`🔗 配对`）。
  - **右栏（58% 宽度）**：自定义 `LyricsScrollView`（`Canvas` 自绘多行滚动歌词，当前演唱行 26sp 高亮放大居中并带辉光，邻近行 18sp 渐隐；支持纯文本歌词或无歌词时的声波律动动画）。
- **遥控器焦点导航模型（消歧明确定义）**：
  - 界面内**仅左侧底部的 7 个播控按钮 `isFocusable = true`**，右侧 `LyricsScrollView` 设为 `isFocusable = false`，避免遥控器焦点掉入歌词区无法返回。
  - **左右方向键（`DPAD_LEFT` / `DPAD_RIGHT`）**：在底部 7 个播控按钮之间水平顺畅切换焦点。
  - **上下方向键（`DPAD_UP` / `DPAD_DOWN`）**：在 `Activity.onKeyDown` 中全局拦截，按上键直接增加音量 `+5%`，按下键直接减小音量 `-5%`，并在屏幕顶部状态栏实时反馈当前音量百分比。
  - **确认键（`DPAD_CENTER` / `ENTER`）**：触发当前聚焦的播控按钮（默认初始焦点在 `⏯ 播放/暂停` 按钮上，因此用户盲按确认键即可一键暂停/继续）。
  - **媒体快捷键（`MEDIA_PLAY_PAUSE` / `MEDIA_NEXT` / `MEDIA_PREVIOUS`）**：全局响应播放/暂停与切歌。

---

## 4. 与现有「玉简」组件的集成点

1. **[`AndroidManifest.xml`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/AndroidManifest.xml) 与 [`proguard-rules.pro`](file:///c:/Users/zhou/Desktop/项目/玉简/app/proguard-rules.pro)**：
   - 在 `AndroidManifest.xml` 中新增 `<uses-permission android:name="android.permission.WAKE_LOCK" />`（供 `SendspinAudioPlayer` 在播放流激活期间持有 `PARTIAL_WAKE_LOCK`），并注册 `<activity android:name=".SendspinActivity" android:exported="true" android:launchMode="singleTask" android:screenOrientation="landscape" android:configChanges="orientation|screenSize|keyboardHidden" />`。
   - 在 `proguard-rules.pro` 中补充 `-keep class com.zqc.tvnotify.SendspinActivity { *; }`。
2. **[`MdnsAdvertiser.kt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/MdnsAdvertiser.kt)**：
   - 改造 `handleQuery` 与 `respond`，分别维护 `askedTvNotify: HashSet<Int>`（针对 `_tvnotify._tcp.local` 与 `YuJian-xxxx._tvnotify._tcp.local`）和 `askedSendspin: HashSet<Int>`（针对 `_sendspin._tcp.local` 与 `YuJian-xxxx._sendspin._tcp.local`）。
   - `_sendspin._tcp.local` 复用经过 ASCII 清洗的 `instanceName()`（即 `YuJian-<token后4位>`）作为 DNS 实例标签，确保 `writeName` 不会因中文或特殊字符产生非法 DNS 标签；将友好中文名称放在 `_sendspin._tcp` 的 TXT 记录中（`path=/sendspin`、`name=${Prefs.getSendspinClientName(appContext)}`）。
   - 当 `Prefs.isSendspinEnabled(appContext)` 为 `true` 且 `Prefs.getSendspinServerUrl(appContext)` 为空（即处于 Server-Initiated 被动发现模式）时，在 `announce()` 与按需响应中回答 `_sendspin._tcp.local` 的 PTR / SRV（端口为 `SendspinManager.boundPort`）/ TXT / A 记录。
3. **[`NotifyService.kt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/NotifyService.kt)**：
   - 在服务启动时启动 `SendspinManager.init(applicationContext)`，在 `onDestroy` 中调用 `SendspinManager.shutdown()`。
   - 当 `SendspinManager` 收到音乐开始播放且 `Prefs.isSendspinAutoPopup(context)` 为 `true` 时，通过 `mainHandler` 启动 `SendspinActivity`（`try { startActivity(...) } catch (_: Exception)`，兼容 Android 6.0 及已授予悬浮窗权限的高版本 Android TV）。
4. **[`MainActivity.kt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/MainActivity.kt)**：
   - 在顶部品牌栏 `headerBar`（`headerBadgeView` 左侧）新增一个醒目的可聚焦按钮 **「🎵 灵音歌词台」**（点击直接进入 `SendspinActivity`，长按弹出 Sendspin 设置与配对码对话框），既丝毫不破坏下方 3×4 操作按钮网格的对称性与一屏高度，又能从主界面一键直达歌词播放器。
5. **[`NotifyHttpServer.kt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/NotifyHttpServer.kt) 与 [`WebConsoleHtml.kt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/WebConsoleHtml.kt)**：
   - **新增路由 `POST /{token}/sendspin`**：
     - 请求体 JSON 支持：
       - `{"action": "open_ui"}`：在电视端打开 `SendspinActivity` 全屏歌词页；
       - `{"action": "close_ui"}`：关闭电视端 `SendspinActivity`；
       - `{"action": "command", "command": "play"|"pause"|"next"|"previous"|"volume"|"mute"|"switch", "volume": 80, "mute": false}`：转发播控命令；
       - `{"action": "config", "enabled": true, "auto_popup": true, "server_url": "", "client_name": "玉简·电视"}`：更新 Sendspin 配置并立即热重载连接。
     - 返回值 JSON：`{"ok": true, "sendspin": { ... }}`。
   - **`GET /health` 与 `POST /{token}/config` 响应扩展**：
     - 在现有返回字段基础上，增加嵌套对象 `"sendspin"`：
       ```json
       "sendspin": {
         "enabled": true,
         "auto_popup": true,
         "connected": false,
         "playing": false,
         "port": 8928,
         "server_name": "",
         "server_url": "",
         "client_name": "玉简·MiTV",
         "title": "",
         "artist": "",
         "album": "",
         "volume": 100,
         "muted": false
       }
       ```
   - **手机 Web 控制台（[`WebConsoleHtml.kt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/WebConsoleHtml.kt)）**：
     - 新增「🎵 Sendspin 灵音歌词台」控制卡片，支持手机端查看当前播放曲目、遥控上一曲/播放暂停/下一曲/音量、一键开启/关闭电视歌词大屏、配置自动弹窗开关与手动服务端地址。
