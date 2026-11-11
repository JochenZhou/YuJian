# 我的世界像素主题、手机 WebUI 适配与 README 完善 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为玉简新增「🧱 方块世界（我的世界像素风格）」主题（含配色、像素草方块图标、8-bit 经验球音效与 WebUI 联动），系统性修复手机端单页长滚动 WebUI 的响应式对齐与溢出问题，完善 `README.md` 文档，并构建发布 `v1.9.0` Release。

**Architecture:** 先在 `Theme.kt`、`ThemeSoundSynth.kt`、`BannerController.kt` 与 `MainActivity.kt` 中以数据驱动方式扩展第 9 款 `Theme.MINECRAFT` 主题，并将主题总数文案改为动态读取 `Theme.entries.size`；再对 `WebConsoleHtml.kt` 重写响应式栅格 CSS（`minmax(0, 1fr)` / `min-width: 0` / `flex-wrap: wrap` / `16px` 表单项 / 3×2 Sendspin 按钮面板 / 防遮挡代码框）并同步 `minecraft` Web 主题，同时扩充 JS 自动化测试保护脚本与响应式样式；最后全面更新 `README.md` 并发布 `v1.9.0`。

**Tech Stack:** Kotlin, Android Framework UI & Canvas/PCM DSP (`AudioTrack`/`MediaPlayer` WAV), Inline HTML5/CSS3 Grid & Vanilla JS, Node.js `node --test`, JUnit 4

**Spec:** [`docs/superpowers/specs/2026-11-11-minecraft-theme-mobile-webui-design.md`](file:///c:/Users/zhou/Desktop/项目/玉简/docs/superpowers/specs/2026-11-11-minecraft-theme-mobile-webui-design.md)

## Global Constraints

- `minSdk = 23`（Android 6.0 Marshmallow），严禁引入 API 24+ 无保护调用，Android Lint `NewApi` 必须零报错。
- 手机端 WebUI **必须保留单页长滚动结构**，不得改成多 Tab 隐藏卡片。
- `MainTabThemeTest.kt` 运行于无 `isReturnDefaultValues` 的 JVM 单测环境，新增断言不可在单测中直接触发 `android.graphics.Color`/`Typeface` 计算属性（可校验构造属性与纯 Kotlin 方法，如 `storageId`、`shortName`、`bannerCornerRadiusDp`、`fromRaw`、文案非空等）。
- `WebConsoleHtml.kt` 必须保留 `app/src/test/js/sendspin-console.test.cjs` 依赖的 SCRIPT 切片锚点（`let sendspinStatus = null;` 至 `setInterval(fetchSendspinStatus, 2500);`）及全部原有元素 ID 与全局 JS 函数名。

## Review Focus

1. **JVM 单测无 Android Stub 异常**：`Theme.MINECRAFT` 的所有非颜色纯 Kotlin 属性（`fromRaw("mc")`、`fromRaw("minecraft")`、`bannerCornerRadiusDp == 0f`、`levelBadgeText`）在 JVM 上可独立测通，且 `ThemeSoundSynth` 为纯数学合成函数无需运行期资源文件。
2. **`Theme.entries` 全枚举穷举覆盖**：检查 `BannerController.ThemeIconView`、`ThemeSoundSynth.getOrGenerateWav`、`Theme` 所有 `when (this)` 分支、以及 `WebConsoleHtml.kt` 的两处 `<select>` 与 `THEME_META`，确保 9 款主题一处不漏。
3. **Flex/Grid 窄屏选项溢出防护**：在 `320px~414px` 手机视口下，所有 `<select>`、长文本 URL、历史记录条、`.status-pill` 徽章与 `Sendspin` 6 连控制按钮均不会横向撑破卡片（全部约束了 `min-width: 0` / `minmax(0, 1fr)` / `max-width: 100%`）。
4. **cURL 复制按钮零文字遮挡**：重构 `.code-box` 结构后，复制按钮置于代码框顶部独立条或留足上内边距，长 cURL 命令折行展示不与按钮重叠。
5. **README 与实际代码 100% 一致**：端口号 `8791`（通知 HTTP）/ `8927`（Sendspin WS 默认端口）、9 款主题 ID 与名称、Sendspin `GET/POST /{token}/sendspin` 参数、目录结构树均与源码核实对齐。

---

### Task 1: 新增 `Theme.MINECRAFT` 主题模型与合成音效 (`Theme.kt`, `ThemeSoundSynth.kt`, `BannerController.kt`, `MainActivity.kt`)

**Files:**
- Modify: `app/src/main/java/com/zqc/tvnotify/Theme.kt`
- Modify: `app/src/main/java/com/zqc/tvnotify/ThemeSoundSynth.kt`
- Modify: `app/src/main/java/com/zqc/tvnotify/BannerController.kt`
- Modify: `app/src/main/java/com/zqc/tvnotify/MainActivity.kt`
- Modify: `app/src/test/java/com/zqc/tvnotify/MainTabThemeTest.kt`

**Interfaces:**
- Produces:
  - `Theme.MINECRAFT`（`storageId = "minecraft"`, `fromRaw("mc" / "pixel" / "block")`）
  - `ThemeSoundSynth` 支持 `Theme.MINECRAFT -> synthMinecraftPixel()`
  - `BannerController.ThemeIconView` 支持 `Theme.MINECRAFT -> drawMinecraftBlock(canvas, w, h)`，`badgeView` 在 `MINECRAFT` 时使用 `0f` 直角半径

- [ ] **Step 1: 在 `MainTabThemeTest.kt` 中补充测试验证 `Theme.MINECRAFT` 别名解析、直角圆角、徽章及全文案**

```kotlin
    @Test
    fun minecraftThemeParsesAliasesAndUsesBlockyBadgeCopy() {
        assertEquals(Theme.MINECRAFT, Theme.fromRaw("minecraft"))
        assertEquals(Theme.MINECRAFT, Theme.fromRaw("mc"))
        assertEquals(Theme.MINECRAFT, Theme.fromRaw("pixel"))
        assertEquals("minecraft", Theme.MINECRAFT.storageId)
        assertEquals(0f, Theme.MINECRAFT.bannerCornerRadiusDp)
        assertFalse(Theme.MINECRAFT.isXianxia)
        assertEquals("[成就]", Theme.MINECRAFT.levelBadgeText(BannerLevel.INFO))
        assertEquals("[苦力怕!]", Theme.MINECRAFT.levelBadgeText(BannerLevel.WARN))
        assertEquals("[下界警报]", Theme.MINECRAFT.levelBadgeText(BannerLevel.ERROR))
        assertTrue(Theme.MINECRAFT.appTitle.contains("CRAFT"))
        assertTrue(Theme.MINECRAFT.testBannerText.isNotBlank())
    }
```

- [ ] **Step 2: 在 `Theme.kt` 中新增 `MINECRAFT` 枚举、别名解析、配色与字体配置**

实现 specs 第 2.1 节全部颜色、文案、`Typeface.MONOSPACE`、`bannerCornerRadiusDp = 0f` 与 `levelBadgeText` / `bannerIconColor` / `bannerStrokeColor`。

- [ ] **Step 3: 在 `ThemeSoundSynth.kt` 中新增 `synthMinecraftPixel()` 8-bit 经验球 + 音符盒提示音**

在 `when (theme)` 中映射 `Theme.MINECRAFT -> synthMinecraftPixel()`，合成三段跳音（`880Hz` -> `1108.73Hz` -> `1318.51Hz`）芯片波（基波 + 奇数次谐波受限叠加）并套用轻混响与平滑淡出。

- [ ] **Step 4: 在 `BannerController.kt` 与 `MainActivity.kt` 中接入方块图标与动态主题数文案**

1. `BannerController.kt`:
   - `ThemeIconView.onDraw` 补充 `Theme.MINECRAFT -> drawMinecraftBlock(canvas, w, h)`：绘制像素网格风格草方块（顶部草绿 `3x3`/`4x4` 阶梯草坪顶盖 + 深褐泥土纹理方块 + 右下高亮 `levelColor` 像素核）。
   - `badgeView` 背景圆角 `when (theme) { Theme.MINECRAFT -> 0f; Theme.CYBERPUNK, Theme.MATRIX -> 3f; else -> 6f }`。
   - 注释与说明中更新 `共 ${Theme.entries.size} 款` / `9 大主题`。
2. `MainActivity.kt`:
   - `showThemeSelectorDialog()` 标题中的 `"共 8 款"` 改为 `"共 ${Theme.entries.size} 款"`。

- [ ] **Step 5: 提交 Task 1**

```bash
git add app/src/main/java/com/zqc/tvnotify/Theme.kt app/src/main/java/com/zqc/tvnotify/ThemeSoundSynth.kt app/src/main/java/com/zqc/tvnotify/BannerController.kt app/src/main/java/com/zqc/tvnotify/MainActivity.kt app/src/test/java/com/zqc/tvnotify/MainTabThemeTest.kt
git commit -m "feat: add Minecraft pixel theme,草方块 icon, and 8-bit XP chime"
```

---

### Task 2: 优化手机端单页 WebUI 对齐与防溢出，联动方块世界主题 (`WebConsoleHtml.kt` + JS 测试)

**Files:**
- Modify: `app/src/main/java/com/zqc/tvnotify/WebConsoleHtml.kt`
- Modify: `app/src/test/js/sendspin-console.test.cjs`

**Interfaces:**
- Consumes: `Theme.MINECRAFT` (`storageId = "minecraft"`, `emoji = "🧱"`, `shortName = "方块世界"`)
- Preserves: `let sendspinStatus = null;` .. `setInterval(fetchSendspinStatus, 2500);` 及所有既有 DOM ID 与 JS 函数

- [ ] **Step 1: 在 `app/src/test/js/sendspin-console.test.cjs` 中补充测试验证手机响应式防护、防遮挡结构与 `minecraft` 主题同步**

新增测试：
1. `mobile layout CSS prevents flex/grid overflow and keeps tap targets usable`：断言 HTML/CSS 中包含 `viewport-fit=cover`、`min-width: 0`、`minmax(0, 1fr)`、`.sendspin-controls`、`flex-wrap: wrap`、`font-size: 16px`、`.code-header`。
2. `console exposes minecraft pixel theme option and metadata`：断言 HTML 包含 `data-theme="minecraft"`、`value="minecraft"`（两处下拉框）以及 `THEME_META.minecraft`。

- [ ] **Step 2: 运行 `node --test app/src/test/js/sendspin-console.test.cjs` 确认新增测试先红 (RED)**

Run: `node --test app/src/test/js/sendspin-console.test.cjs`
Expected: FAIL on newly added layout & minecraft tests (existing 7 pass).

- [ ] **Step 3: 升级 `WebConsoleHtml.kt` 手机单页响应式布局与方块世界样式**

1. **CSS `minecraft` 主题与方块质感**：
   - 新增 `body[data-theme="minecraft"]` 变量组（`--bg: #121612; --card-bg: #1d221d; --card-stroke: #3b4739; --text: #f3f7ef; --text-muted: #a0b39a; --accent: #55cc44; ...`）以及 `body[data-theme="minecraft"] .card, button, input, select, textarea` 硬朗像素直角（`border-radius: 2px; border-width: 2px;`）与等宽标题增强。
2. **手机响应式 CSS 与结构对齐修复（保留单页长滚动）**：
   - `viewport` 改用 `width=device-width, initial-scale=1.0, viewport-fit=cover`，`body` 适配 `padding: 14px 12px calc(24px + env(safe-area-inset-bottom)); overflow-x: hidden;`。
   - `.status-pill` 改用 `display: flex; flex-wrap: wrap; align-items: center; gap: 4px 10px; border-radius: 12px;`；`.card-title` 增加 `flex-wrap: wrap; gap: 6px 10px;`。
   - `.row` 改用 `display: flex; flex-wrap: wrap; gap: 10px;` 且 `.col { flex: 1 1 0; min-width: 0; }`；新增 `.grid-params`（宽屏 3 列 `repeat(3, minmax(0, 1fr))`，`<= 560px` 自动改为 `repeat(2, minmax(0, 1fr))` 且第 3 项 duration 整行通栏 `grid-column: 1 / -1`）及 `.grid-2`（宽屏 2 列 `repeat(2, minmax(0, 1fr))`，`<= 480px` 自动堆叠 `1fr`）。
   - 所有 `input, select, textarea` 设置 `width: 100%; max-width: 100%; min-width: 0; font-size: 16px; min-height: 44px;`，`select` 加 `overflow: hidden; text-overflow: ellipsis;`。
   - `.checkbox-grid` 使用 `grid-template-columns: repeat(2, minmax(0, 1fr))`（`<= 360px` 切 `1fr`），`.checkbox-item { min-height: 44px; min-width: 0; }`。
   - **Sendspin 6 键面板**：改为 `<div class="sendspin-controls">`，样式为 `display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin-top: 12px;`（按钮 `min-height: 44px; padding: 10px 6px; font-size: 13px;`），使 6 键永远呈现清晰工整的 `3×2` 对称布局。
   - `#sendspin-sync` 增加弱底框和 `overflow-wrap: anywhere`；应用同步、保存与打开按钮使用 `.grid-2` 对齐。
   - `.history-item` 内部 `.history-info { min-width: 0; overflow-wrap: anywhere; }`、`.history-meta { flex-wrap: wrap; }`、`.history-btn { flex-shrink: 0; min-height: 36px; }`。
   - `.code-box` 顶部增加 `<div class="code-header"><span>cURL 命令</span><button type="button" class="copy-btn" onclick="copyCurl()">复制</button></div>`，取消 `position: absolute`，彻底杜绝遮挡。
3. **HTML 主题下拉与 JS `THEME_META`**：
   - `#req-theme` 和 `#config-theme` 补充 `<option value="minecraft">🧱 方块世界（草方块像素 · 8-bit 经验球音）</option>`；
   - `THEME_META` 新增 `minecraft: { emoji: "🧱", name: "方块世界", classical: false }`。

- [ ] **Step 4: 运行 JS 测试确认全部通过 (GREEN) 并提交 Task 2**

Run: `node --test app/src/test/js/sendspin-console.test.cjs`
Expected: PASS (9/9)

```bash
git add app/src/main/java/com/zqc/tvnotify/WebConsoleHtml.kt app/src/test/js/sendspin-console.test.cjs
git commit -m "feat(webui): optimize mobile responsive alignment and add Minecraft theme"
```

---

### Task 3: 全面完善 `README.md` 并发布 `v1.9.0`

**Files:**
- Modify: `README.md`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: 更新 `README.md` 对齐最新特性、9 款主题、Sendspin 音乐与项目结构**

覆盖以下更新点（对照当前实际源码准确编写，不写未实现功能）：
- 顶部简介与功能总览表格：补充 **Sendspin 多房间同步音乐**、**黑胶转盘 + LRC/`lrclib.net` 同步歌词播放器**、**顶部 3 页签焦点即切 TV 主页**、**9 大美学主题 + 原生 WAV 合成音效**、**手机自适应单页 Web 控制台**。
- **9 大主题一览表**：完整列出 `xiuxian`, `default`, `palace`, `cyberpunk`, `wukong`, `matrix`, `ink_light`, `gamer`, `minecraft`（名称、风格与合成提示音）。
- **Sendspin 同步音乐专题**：说明 mDNS（`_sendspin._tcp`）自动发现 / WebSocket `8927` 手动配置、默认开启的 Kalman + `AudioTimestamp` 自动输出同步闭环与 WebUI 手动固定补偿切换、全屏黑胶与同步歌词播放页操作（遥控器方向键 / OK / 返回 / 菜单快捷键）。
- **接口文档补充**：新增 `GET/POST /{token}/sendspin` 与 `POST /{token}/config` 的 `theme` 参数说明，修正 `/health` 示例版本号与字段说明。
- **目录结构更新**：准确列出最新源码结构（`MainActivity.kt`, `Theme.kt`, `ThemeSoundSynth.kt`, `SendspinActivity.kt`, `SendspinPlayerViews.kt`, `SendspinRuntime.kt`, `SendspinPcmPump.kt`, `AndroidAudioPlayer.kt`, `LyricsFetcher.kt`, `WebConsoleHtml.kt` 等）。

- [ ] **Step 2: 执行代码审查 -> 通过后升版 `app/build.gradle.kts` (`versionCode = 46`, `versionName = "1.9.0"`) -> 推送 GitHub Actions 与 `v1.9.0` Release 标签**

```bash
node --test app/src/test/js/sendspin-console.test.cjs
git add README.md docs/superpowers/specs/2026-11-11-minecraft-theme-mobile-webui-design.md docs/superpowers/plans/2026-11-11-minecraft-theme-mobile-webui.md
git commit -m "docs: refresh README with Sendspin, 3-tab TV UI, 9 themes, and mobile WebUI"
```
审查确认后升版并打标签 `v1.9.0` 推送触发自动 Release 构建。
