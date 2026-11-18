# 当前歌单详单选歌播放与 MA 音量双向控制 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为电视端播放器增加「当前歌单详单 + 选歌点播」能力（控制栏右侧改为「歌单」，「设置」移至顶部右上角），并将播放器音量升级为与 Music Assistant (MA) 双向实时同步控制，同时在 `ha-yujian` 集成侧补齐歌单曲目同步与单曲点播支持。

**Architecture:**
1. **曲目同步与单曲点播（`ha-yujian` ↔ `YuJian`）**：`ha-yujian` 在通过 `media_player.browse_media` 同步歌单时并发抓取每张歌单内的曲目列表（`tracks: [{id, name, artist}]`）随 `POST /{token}/library` 推送到电视；电视端 `PlaylistStore` 保存曲目列表并维护/自动匹配「当前播放歌单」，点击单曲时通过现有 Webhook（`POST /api/yujian/play`）附带 `track_id` 触发 MA 播放该曲目及后续歌单队列。
2. **播放器布局重排（`SendspinActivity`）**：底部控制栏调整为 `音量 | 上一首 | 播放 | 下一首 | 歌单`，原「设置」按钮移至顶部 `header` 最右侧（与左侧「返回」按钮对称）。
3. **MA 音量双向控制（`SendSpinClient` + `SendspinRuntime`）**：电视端调音量/静音时同时调用 `sendControllerCommand("volume" / "mute")` 控制 MA 音量并更新本地增益；当 MA 侧通过 `server/state (controller.volume)` 或 `server/command (player.volume)` 改变音量时，实时通知 `SendspinRuntime` 刷新电视 UI。

**Tech Stack:** Kotlin (Android `minSdk = 23`, Robolectric 4.14.1, JUnit 4, OkHttp, Moshi), Python 3.11+ (Home Assistant `custom_components/yujian`, `pytest`).

---

## Global Constraints

- **Android 最低版本兼容**：`minSdk = 23`（Android 6.0），严禁在无版本守卫下调用 API 24+ 方法（Lint 启用 `NewApi` 检查）。
- **零新增第三方依赖**：电视端与 HA 集成均不引入任何新的第三方库。
- **向后兼容**：电视端解析 `/library` 时对缺失 `tracks` 字段的老版 HA 负载保持兼容；HA Webhook 对不含 `track_id` 的整单播放请求保持原有行为。
- **禁止 ADB 安装**：完成后推送到 GitHub 编译打包，绝不通过 `adb` 安装到 `192.168.101.14`。

## Review Focus

1. **老版本或空曲目歌单降级**：当某张歌单没有 `tracks`（或为空数组）时，打开详单仍显示「▶ 从头播放整张歌单」与「切换其他歌单」，点击可正常整单点播，不崩溃。
2. **当前播放歌单自动识别**：未在电视手动选歌单、直接从手机/MA 播放某首歌时，`PlaylistStore.resolveActivePlaylist(title, artist)` 能通过当前曲目名称匹配到所属歌单并在详单中高亮「正在播放」。
3. **顶部栏与底部控制栏遥控器焦点闭环**：`设置` 移至顶部栏右侧后，`返回` ⇄ `设置` 左右可达，封面按上键可达顶部栏，底部控制栏 `音量 | 上一首 | 播放 | 下一首 | 歌单` 左右焦点顺序完整。
4. **MA 音量双向同步不回跳**：当用户在 MA 网页端调节音量触发 `server/command` 或 `server/state` 时，电视端 `currentStatus.volume` 与音量弹窗滑块实时同步（拖拽中 `volumeDialogEditing == true` 时不强行打断用户手势）。
5. **单曲点播后继续播放歌单后续曲目**：`ha-yujian` 收到 `track_id` 时，优先将该歌单从所选曲目起的 URI 列表传给 `music_assistant.play_media`（`enqueue="replace"`），无该服务时回退到 `media_player.play_media` 播放所选单曲并追加后续曲目。

---

### Task 1: 电视端歌单曲目模型、当前歌单匹配与单曲点播请求 (`PlaylistCatalog.kt` & `PlaylistPlayer.kt`)

**Files:**
- Modify: `app/src/main/java/com/zqc/tvnotify/PlaylistCatalog.kt`
- Modify: `app/src/main/java/com/zqc/tvnotify/PlaylistPlayer.kt`
- Test: `app/src/test/java/com/zqc/tvnotify/PlaylistCatalogTest.kt`

**Interfaces:**
- Produces:
  - `data class PlaylistTrack(val id: String, val name: String, val artist: String? = null)`
  - `data class PlaylistEntry(val id: String, val name: String, val trackCount: Int?, val coverBase64: String?, val tracks: List<PlaylistTrack> = emptyList())`
  - `PlaylistStore.markActivePlaylist(playlistId: String?)`
  - `PlaylistStore.resolveActivePlaylist(currentTitle: String?, currentArtist: String? = null): PlaylistEntry?`
  - `PlaylistTrack.matchesPlaying(currentTitle: String?, currentArtist: String? = null): Boolean`
  - `PlaylistPlayer.request(url: String, token: String, id: String, name: String, trackId: String? = null, trackName: String? = null, onResult: (ok: Boolean, error: String?) -> Unit)`

- [ ] **Step 1: 在 `PlaylistCatalogTest.kt` 中编写曲目解析与当前歌单匹配测试**
  - 测试 `/library` JSON 中 `playlists[i].tracks`（含 `id`, `name`, `artist`）的解析，以及 `track_count` 缺失时自动回退为 `tracks.size`。
  - 测试 `PlaylistStore.resolveActivePlaylist(currentTitle, currentArtist)`：
    1. 已调用 `markActivePlaylist(id)` 且该歌单仍存在时优先返回该歌单（但若当前正在播放的歌曲明确属于另一张歌单且不在当前标记歌单中，自动切换到匹配歌单）；
    2. 未标记时，按 `currentTitle`（忽略大小写与首尾空白）在各歌单 `tracks` 中查找并返回匹配的 `PlaylistEntry`。

- [ ] **Step 2: 实现 `PlaylistCatalog.kt` 与 `PlaylistPlayer.kt` 扩展**
  - 在 `PlaylistCatalog.parse` 中解析可选的 `tracks` JSONArray，过滤掉无 `id` 的项。
  - 在 `PlaylistPlayer.request` 中当 `trackId` 非空时向请求 JSON 写入 `"track_id"` 与 `"track_name"`。

---

### Task 2: Sendspin 协议与运行时双向同步 MA 音量 (`SendSpinClient.kt` & `SendspinRuntime.kt`)

**Files:**
- Modify: `third_party/sendspin-jvm/sendspin-protocol/src/main/kotlin/com/sendspin/protocol/SendSpinClient.kt`
- Modify: `app/src/main/java/com/zqc/tvnotify/SendspinRuntime.kt`
- Test: `third_party/sendspin-jvm/sendspin-protocol/src/test/kotlin/com/sendspin/protocol/MessageParserTest.kt`

**Interfaces:**
- Produces:
  - `SendSpinClient.playerVolumeFlow: StateFlow<Int>` 与 `SendSpinClient.playerMutedFlow: StateFlow<Boolean>`（在 `handleServerHello`、`ServerCommand("volume"/"mute")`、`setLocalVolume`、`setLocalMuted` 时实时更新）
  - `SendspinRuntime.command("volume", volume)` 与 `command("mute", muted)`：
    - 若 `"volume" in controllerCommands`，调用 `current.sendControllerCommand("volume", volume = clamped)`；
    - 若 `"volume" in playerSupported`，调用 `current.setLocalVolume(clamped)` 并更新 `audioPlayer.setVolume(gain)`；
    - 只要任一支持即返回 `true` 并更新 `Status(volume, muted)`。
  - `SendspinRuntime.updateFromServerState` 与 `watch`：
    - 监听 `instance.playerVolumeFlow` 和 `instance.playerMutedFlow`；
    - 当 `message.controller` 带有非空 `volume` / `muted` 时，同步更新 `currentStatus.volume` / `currentStatus.muted` 并应用到本地 `setLocalVolume` / `setLocalMuted`（保持电视输出与 MA 控制器音量一致）。

- [ ] **Step 1: 在 `SendSpinClient.kt` 中暴露响应式音量/静音流**
  - 新增 `_playerVolumeFlow = MutableStateFlow(playerVolume)` 与 `_playerMutedFlow = MutableStateFlow(playerMuted)`，在 `setLocalVolume`、`setLocalMuted` 以及 `ServerCommand` 处理 `"volume"` / `"mute"` 分支中同步更新。

- [ ] **Step 2: 在 `SendspinRuntime.kt` 中打通双向音量同步**
  - 在 `watch(instance)` 中收集 `instance.playerVolumeFlow` 与 `instance.playerMutedFlow`，实时刷新 `currentStatus.volume`、`currentStatus.muted` 和 `playerGain`。
  - 在 `updateFromServerState` 中，若 `lastController?.volume != null` 或 `lastController?.muted != null`，将其作为最新 MA 音量同步到 `Status`。
  - 在 `command("volume")` / `command("mute")` 中同时支持向 MA 发送 `sendControllerCommand` 与更新本地 `setLocalVolume` / `setLocalMuted`。

---

### Task 3: 播放器界面布局重排与「当前歌单详单」弹窗 (`SendspinPlayerViews.kt` & `SendspinActivity.kt`)

**Files:**
- Modify: `app/src/main/java/com/zqc/tvnotify/SendspinPlayerViews.kt`
- Modify: `app/src/main/java/com/zqc/tvnotify/SendspinActivity.kt`
- Test: `app/src/test/java/com/zqc/tvnotify/SendspinPlayerStyleTest.kt`

**Interfaces:**
- Produces:
  - `SendspinIcon.PLAYLIST`（绘制三横线+音符/播放指示的歌单矢量图标）
  - 顶部栏 `header`：`[back]  玉简 / SENDSPIN  [status]  [settings]`
  - 底部控制栏 `controls`：`[volumeControl]  [previous]  [playPause]  [next]  [playlistButton]`
  - `SendspinActivity.showCurrentPlaylistOrCatalogDialog()`：
    - 若 `PlaylistStore.resolveActivePlaylist(currentStatus.title, currentStatus.artist)` 非空，直接打开 `showPlaylistDetailDialog(activeEntry, isCurrent = true)`；
    - 否则打开 `showPlaylistDialog()`（歌单总览），点击任一歌单进入 `showPlaylistDetailDialog(entry, isCurrent = false)`。
  - `SendspinActivity.showPlaylistDetailDialog(entry: PlaylistEntry, isCurrent: Boolean)`：
    - 置顶项 1：`⇄ 切换其他歌单`（副标题：`共 N 张歌单，点击切换`，徽标：`全部歌单`）
    - 置顶项 2：`▶ 从头播放整张歌单`（副标题：`共 M 首`，徽标：`整单播放`）
    - 曲目列表项：`01. <track.name>`（副标题：`track.artist`，当前正在播放的曲目徽标为 `正在播放` 且高亮，其余为 `点播`），点击调用 `playTrack(entry, track)`。

- [ ] **Step 1: 在 `SendspinPlayerViews.kt` 中新增 `SendspinIcon.PLAYLIST`**
  - 在 `SendspinIcon` 枚举中添加 `PLAYLIST`，并在 `SendspinIconDrawable.draw` 中绘制清晰的歌单矢量图标。

- [ ] **Step 2: 在 `SendspinActivity.kt` 中调整顶部栏与控制栏布局及焦点链**
  - 将 `settings` 移入 `header` 最右侧（位于 `status` 右侧，`leftMargin = dp(12)`）；
  - 在 `controls` 最右侧新增 `playlistButton`（`SendspinFocusPill`，图标 `SendspinIcon.PLAYLIST` + 文字 `歌单`）；
  - 更新 `updateFocusTargets()`：
    - 控制栏按钮行：`listOf(volumeControl, previous, playPause, next, playlistButton)`；
    - 顶部栏：`back.nextFocusRightId = settings.id`，`settings.nextFocusLeftId = back.id`，`settings.nextFocusDownId = artworkSwitcher.id`。
  - 更新音量可用性判断：`val canAdjustVolume = connected && ("volume" in state.playerCommands || "volume" in state.controllerCommands || "mute" in state.playerCommands || "mute" in state.controllerCommands)`。

- [ ] **Step 3: 实现 `showPlaylistDetailDialog` 与 `playTrack`**
  - 实现歌单详单弹窗与单曲点播逻辑，并在 `SendspinPlayerStyleTest.kt` 中添加布局、焦点链与歌单详单弹窗单元测试。

---

### Task 4: `ha-yujian` 集成侧歌单曲目抓取与单曲点播支持 (`playlists.py`, `media.py`, `webhook.py`, `__init__.py`)

**Files (in `https://github.com/JochenZhou/ha-yujian`):**
- Modify: `custom_components/yujian/playlists.py`
- Modify: `custom_components/yujian/media.py`
- Modify: `custom_components/yujian/webhook.py`
- Modify: `custom_components/yujian/__init__.py`
- Test: `tests/test_playlists.py`, `tests/test_media.py`, `tests/test_webhook.py`

**Interfaces:**
- Produces:
  - `playlists.track_item(name: str, media_id: str, artist: str | None = None) -> dict[str, Any]`
  - `playlists.playlist_item(name: str, media_id: str, cover_b64: str | None = None, track_count: int | None = None, tracks: list[dict[str, Any]] | None = None) -> dict[str, Any]`
  - `media.async_list_playlist_tracks(hass, entity_id: str, playlist_id: str) -> list[dict[str, Any]]`
  - `media.async_play_track(hass, entity_id: str, playlist_uri: str, track_uri: str, track_uris: list[str] | None = None) -> None`
  - `webhook.YujianPlayView.post`: 支持可选 `track_id` 参数，传入时调用 `async_play_track` 播放指定歌曲并顺延后续歌单曲目。

- [ ] **Step 1: 克隆 `ha-yujian` 仓库并扩展 `playlists.py`、`media.py`、`webhook.py`、`__init__.py`**
  - `async_list_playlist_tracks` 通过 `async_browse(hass, entity_id, "playlist", playlist_id)` 获取歌单子项曲目；
  - `fetch_covers`（或歌单组装阶段）并发拉取每张歌单的 `tracks`，填入 `playlist_item(..., track_count=len(tracks), tracks=tracks)`，并在 `runtime["playlist_tracks"][media_id]` 缓存曲目 URI 顺序列表供 Webhook 点播使用；
  - `async_play_track` 从所选 `track_uri` 截取该歌单后续曲目列表 `[track_i, track_i+1, ...]`：若存在 `music_assistant.play_media` 服务则一次传入 URI 列表（`enqueue="replace"`），否则通过 `media_player.play_media` 立即播放 `track_uri`（`enqueue="replace"`）并依次将后续曲目加入队列（`enqueue="add"`）。

- [ ] **Step 2: 编写并运行测试，推送两个仓库到 GitHub**
  - 运行本地 `node --test app/src/test/js/sendspin-console.test.cjs`；
  - 提交并推送 `YuJian`（升级版本号 `v2.0.0` / `versionCode = 55`）到 GitHub Actions 执行单元测试、Lint 与 Release APK 打包；
  - 提交并推送 `ha-yujian` 到 GitHub。
