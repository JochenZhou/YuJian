# 我的世界像素风主题、手机端 WebUI 适配与 README 完善设计文档

## 1. 背景与目标

基于当前 `1.8.0`（`versionCode = 45`）版本，本次迭代聚焦四项需求，最终发布 **`v1.9.0`**（`versionCode = 46`）：
1. **新增「我的世界像素风格（Minecraft Pixel）」第 9 套美学主题**（选定经典草方块 / 翡翠像素风 A 方案）：覆盖配色系统、方正 `0dp` 直角方块横幅、像素风 3D 草方块自绘图标、8-bit 经验球（XP Orb）+ 音符盒合成提示音、TV 端双语文案与 Web 控制台联动。
2. **优化手机端 WebUI（[`WebConsoleHtml.kt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/WebConsoleHtml.kt)）排版与自适应对齐**：保留用户选择的**单页长滚动结构**，系统性修复窄屏手机上存在的 Flex 子项溢出、3 列表单挤压、Sendspin 6 键同排过窄、状态胶囊不换行、历史项文字挤出按钮、cURL 复制按钮遮挡代码、iOS 输入聚焦自动放大等问题。
3. **全面完善 [`README.md`](file:///c:/Users/zhou/Desktop/项目/玉简/README.md)**：同步最新功能矩阵（Sendspin 自动同步音乐、黑胶转盘与同步歌词播放器、3-Tab 焦点即切大屏主页、9 大主题与原生 DSP 合成音效、手机 WebUI、Sendspin HTTP 接口与最新项目目录结构）。
4. **升版并发布 `v1.9.0` Release**：经代码审查与单元测试/Lint 验证后提交并推送 GitHub Actions 构建并自动发布 Release。

---

## 2. 模块一：我的世界像素风主题 (`Theme.MINECRAFT`)

### 2.1 主题定义与配色（[`Theme.kt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/Theme.kt)）
- 枚举常量：`MINECRAFT`
- `storageId`: `"minecraft"`（别名支持 `"mc"`, `"pixel"`, `"block"`, `"minecraft_pixel"`）
- 视觉标识：`emoji = "🧱"`, `shortName = "方块世界"`, `fullName = "🧱 方块世界（草方块像素 · 8-bit 经验球音）"`, `soundDesc = "8-bit 经验球清脆提示音"`
- 语境属性：`isXianxia = false`，`isLightBanner = false`
- 配色（深石岩灰底 + 泥土/圆石层级 + 草方块翠绿主强调 + 绿宝石/火把/红石分级色）：
  - `windowBg`: `#FF121612`（深石板黑绿底）
  - `cardBg`: `#FF1D221D`（深圆石块底色）
  - `cardStroke`: `#FF3B4739`（方块石纹灰绿描边）
  - `hintColor`: `#FFA0B39A`（苔石浅灰绿）
  - `accentColor`: `#FF55CC44`（经典草方块翡翠绿）
  - `buttonBg`: `#FF2A3229`（石砖按钮底）
  - `buttonStroke`: `#FF4D5C49`（凸起亮石描边）
  - `bannerBackgroundStart` / `End`: `#F6232923` -> `#FA141814`（高不透明度方正方块面板）
  - `bannerTitle`: `#FFFFFFFF` / `bannerBodyText`: `#FFD8E8D2`
  - `bannerTitleTypeface` / `bannerBodyTypeface`: `Typeface.MONOSPACE`（方块像素感等宽字体）
  - `bannerCornerRadiusDp`: `0f`（硬朗纯直角像素方块外观）
  - `levelBadgeText`: `INFO -> "[成就]"` / `WARN -> "[苦力怕!]"` / `ERROR -> "[下界警报]"`
  - `bannerIconColor`: `INFO -> #55FF55`（经验球翠绿） / `WARN -> #FFAA00`（金锭琥珀） / `ERROR -> #FF5555`（红石赤红）
  - `appTitle`: `"YUJIAN // CRAFT_HUD"`
  - `appSubtitle`: `"PIXEL OVERLAY · 像素方块大屏通知终端"`
  - `defaultBannerTitle`: `"新物品入袋"` / `testBannerTitle`: `"💎 已解锁成就：千里传音"` / `testBannerText`: `"[经验球 +5] 方块世界像素横幅已就绪，随时接收红石与 Home Assistant 信号。"`

### 2.2 动态主题总数文案
- 将 [`MainActivity.kt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/MainActivity.kt)、[`BannerController.kt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/BannerController.kt) 中硬编码的 `"共 8 款"` / `"8 大主题"` 改为动态读取 `${Theme.entries.size}`（当前共 9 款）。

### 2.3 专属自绘像素草方块图标与直角徽章（[`BannerController.kt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/BannerController.kt)）
- `ThemeIconView.onDraw` 新增 `Theme.MINECRAFT -> drawMinecraftBlock(canvas, w, h)`：
  - 关闭抗锯齿或在整数像素网格上绘制经典等距立方体/正方草方块：顶部翠绿草坪像素格点阵、前/侧深褐泥土像素明暗网格、右下角嵌高亮 `levelColor` 像素徽标框。
- 标题栏 `badgeView` 圆角同步将 `Theme.MINECRAFT` 设为 `0f`（与 `CYBERPUNK`/`MATRIX` 的 `3f` 区分，呈纯正直角像素徽章）。

### 2.4 8-bit 经验球 + 音符盒合成音效（[`ThemeSoundSynth.kt`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/main/java/com/zqc/tvnotify/ThemeSoundSynth.kt)）
- 新增 `synthMinecraftPixel()`：
  - 模拟 Minecraft 拾取经验球（XP Orb）+ Note Block（木琴/竖琴芯片音）的三段短促芯片音律：
    - 音符 1（`0.00s ~ 0.085s`）：A5（`880Hz`）芯片方波 + 三角波混合，快速包络衰减；
    - 音符 2（`0.085s ~ 0.18s`）：C#6（`1108.73Hz`）跃迁音，产生经典经验球叮铃感；
    - 音符 3（`0.175s ~ 0.48s`）：E6（`1318.51Hz`）+ 八度泛音（`2637.02Hz`）清亮落音 + 轻混响。
  - 更新 `ThemeSoundSynth.CACHE_VERSION` 为 `"v2"`（或维持 `v1` 不变，因按 `${theme.storageId}` 命名缓存文件 `theme_sound_v1_minecraft.wav`，不与旧缓存冲突；保留 `"v1"` 即可零冗余重生成）。

---

## 3. 模块二：手机端 WebUI 单页响应式布局与防溢出优化 (`WebConsoleHtml.kt`)

保留当前单页 5 张卡片（发送通知、电视调控、Sendspin 音乐、历史记录、cURL）的纵向浏览结构，解决全部窄屏错位、溢出、挤压和触控难点：

1. **防溢出基础修复（解决选项溢出框外问题）**：
   - `.row > *` 与 `.col` 统一强制 `min-width: 0;`。
   - 所有 `<input>`、`<textarea>`、`<select>` 统一设置 `width: 100%; max-width: 100%; min-width: 0; font-size: 16px; min-height: 44px;`，彻底消除 Android/iOS 下拉长文本把父容器顶破边框的溢出问题，同时避免 iOS Safari 聚焦输入框自动放大页面。
   - `<select>` 设置 `text-overflow: ellipsis; overflow: hidden; white-space: nowrap; padding-right: 28px;`。
2. **顶部与状态栏自适应**：
   - `viewport` 调整为 `width=device-width, initial-scale=1.0, viewport-fit=cover`，`body` 底部内边距使用 `calc(20px + env(safe-area-inset-bottom))`。
   - `.status-pill` 改为 `display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; border-radius: 12px;` 防止窄屏右侧溢出。
   - `.card-title` 增加 `flex-wrap: wrap; gap: 6px;`，避免副标题/API路径与卡片标题碰撞。
3. **表单、按钮与开关栅格对齐**：
   - **通知参数网格**：`.row-params` 在桌面为 `3` 列（`grid-template-columns: repeat(3, minmax(0, 1fr))`），在 `<= 560px` 手机屏自动重排为 `2` 列（其中 `duration` 或独立占满一行 `grid-column: 1 / -1`），彻底告别 3 列挤在 360px 内的截断现象。
   - **配置行与双按钮行**：`<= 560px` 时 `.row-responsive` 优雅折行或设为 `1fr` / `repeat(2, minmax(0, 1fr))`，按钮全部保证 `min-height: 44px` 舒适点击区，消除文字垂直挤压。
   - **复选框网格 `.checkbox-grid`**：使用 `grid-template-columns: repeat(2, minmax(0, 1fr))`，卡片宽度 `< 360px` 时自动切为 `1fr`，每项 `min-height: 44px`。
   - **Sendspin 控制区（6 键重排）**：改用 `.sendspin-controls`（`display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px;`），形成规整对称的 `3×2` 遥控面板，并将应用同步、保存连接按钮用统一 `.grid-2` 对齐。`#sendspin-sync` 增加弱底框和 `overflow-wrap: anywhere`。
4. **历史记录与 cURL 代码卡片修复**：
   - `.history-info` 加 `min-width: 0; flex: 1 1 auto; overflow-wrap: anywhere;`；`.history-meta` 加 `flex-wrap: wrap;`；`.history-btn` 加 `flex-shrink: 0; min-height: 36px; padding: 6px 12px;`，防止长通知把重发按钮挤出卡片。
   - `.code-box` 顶部增加专属工具条（标题行/右侧复制按钮），替代原先 `position: absolute` 悬浮压住第一行 cURL 代码的设计。
5. **联动「我的世界」像素主题**：
   - WebUI CSS 新增 `body[data-theme="minecraft"]` 配色与方块感直角样式（`border-radius: 2px; border-width: 2px;` 作用于卡片/按钮/输入框），两个主题下拉框与 JS `THEME_META` 同步加入 `minecraft`。
   - 完全保留 [`sendspin-console.test.cjs`](file:///c:/Users/zhou/Desktop/项目/玉简/app/src/test/js/sendspin-console.test.cjs) 所依赖的所有 DOM ID、函数签名与脚本起止标记 `let sendspinStatus = null;` .. `setInterval(fetchSendspinStatus, 2500);`。

---

## 4. 模块三：README.md 全面完善 & 版本发布 (`v1.9.0`)

- 更新 [`README.md`](file:///c:/Users/zhou/Desktop/项目/玉简/README.md)：
  - 修正版本号为最新 `1.9.0`，补充 TV 端**顶部 3 页签焦点即切大屏主页**说明；
  - 新增完整 **Sendspin 多房间同步音乐**章节（自动发现/手填 URL、自动时钟+硬件反馈同步闭环、黑胶唱片转盘 UI、同步 LRC/`lrclib.net` 歌词）；
  - 扩充**9 大美学主题 + 纯代码 DSP 合成音效**对照表（含最新 `🧱 方块世界` 像素风）；
  - 补充 **Sendspin 控制接口 (`GET/POST /{token}/sendspin`)** 与更新后的**目录结构树**。
- 升级 [`app/build.gradle.kts`](file:///c:/Users/zhou/Desktop/项目/玉简/app/build.gradle.kts) 至 `versionCode = 46`, `versionName = "1.9.0"`，代码审查通过并确认本地与 GitHub Actions 全部测试/Lint 通过后推送 `v1.9.0` 标签触发正式 Release。
