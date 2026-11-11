# 主页 Tab 化重构 (Scheme A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `MainActivity` 主页重构为顶部 3 个一级 Tab（总览与互联 / 演练与调控 / 系统与运维）的双栏免滚动布局，支持遥控器焦点移上即刻切换页面。

**Architecture:** 在 `Theme.kt` 中补充一级主导航 Tab 与分栏卡片的双语文案；在 `MainActivity.kt` 中新增 `MainTab` 枚举与顶部主导航栏，将原有单屏堆叠控件拆分为 `overviewPage`、`practicePage`、`systemPage` 三个独立双栏页面容器，并通过焦点监听（`OnFocusChangeListener`）实现焦点即切、左右焦点边界锁死、选中态视觉保持以及返回键回退导航。

**Tech Stack:** Kotlin, Android Framework Views (`LinearLayout`, `FrameLayout`, `Button`, `StateListDrawable`), JUnit 4

**Spec:** [`docs/superpowers/specs/2026-11-11-main-home-tabs-design.md`](file:///c:/Users/zhou/Desktop/项目/玉简/docs/superpowers/specs/2026-11-11-main-home-tabs-design.md)

## Global Constraints

- `minSdk = 23`（Android 6.0 Marshmallow），严禁使用高于 API 23 的无保护方法，确保 Android Lint `NewApi` 零报错。
- 主页 `Sendspin 音乐` 按钮文案必须保持为纯文本 `"Sendspin 音乐"`（不带 `♫` 等音乐图标）。
- 顶部主 Tab 栏在遥控器焦点移入（`hasFocus == true`）时立即切换下方显示的 Tab 页面。
- 在 Tab 2（演练与调控）切换主题重建视图后，必须保持停留在 Tab 2 并将焦点恢复到 `themeButton`。

## Review Focus

1. **焦点移离顶部 Tab 进入内容区时的视觉指示**：当焦点从顶部主 Tab 按钮下移到页面内部按钮时，当前激活的顶部主 Tab 按钮虽失去焦点（`state_focused = false`），仍须保持高亮选中样式（半透明主题色背景 + `2dp` 主题色描边 + 白字），不能退化成普通未选中灰色按钮。
2. **顶部 Tab 栏左右方向键越界**：在最左侧 `overviewTabButton` 按左键或在最右侧 `systemTabButton` 按右键时，焦点必须锁定在自身（`nextFocusLeftId` / `nextFocusRightId`），防止跳入下方右侧卡片。
3. **主题切换后的 Tab 状态与焦点恢复**：在 `MainTab.PRACTICE` 切换主题触发 `setContentView(buildRootView())` 时，`activeMainTab` 不能重置回 `OVERVIEW`，否则 `themeButton.requestFocus()` 会对 `View.GONE` 的视图请求焦点导致焦点丢失。
4. **首屏与演练页双重快捷按钮同步**：Tab 1 的快捷「试传一简」「传音简录」与 Tab 2 的「试传一简」「传音简录」使用各自独立的 `Button` 实例，绑定相同的点击/长按处理函数，避免同一个 `View` 被添加到两个父容器抛 `IllegalStateException`。
5. **遥控器返回键层级体验**：当焦点在内容区时按返回键，优先将焦点移回当前顶部主 Tab 按钮；当焦点已在顶部主 Tab 按钮且处于非首屏 Tab 时，按返回键切回 `MainTab.OVERVIEW`；处于 `MainTab.OVERVIEW` 顶部 Tab 时正常退出页面。

---

### Task 1: 主页 Tab 模型与主题双语文案 (`Theme.kt` + 单元测试)

**Files:**
- Modify: `app/src/main/java/com/zqc/tvnotify/Theme.kt`
- Test: `app/src/test/java/com/zqc/tvnotify/MainTabThemeTest.kt`

**Interfaces:**
- Produces:
  - `enum class MainTab { OVERVIEW, PRACTICE, SYSTEM }`（定义于 `Theme.kt` 或同包下供 `MainActivity` 与单测直接访问）
  - `Theme.mainTabTitle(tab: MainTab): String`
  - `Theme.quickActionsTitle: String`
  - `Theme.practiceCardTitle: String` / `Theme.practiceCardSubtitle: String` / `Theme.practiceCardHint: String`
  - `Theme.controlCardTitle: String` / `Theme.controlCardSubtitle: String` / `Theme.controlCardHint: String`
  - `Theme.systemCardTitle: String` / `Theme.systemCardSubtitle: String`
  - `Theme.keepAliveCardTitle: String`

- [ ] **Step 1: 编写 `MainTabThemeTest.kt` 验证 3 个主 Tab 及卡片文案在古风与现代主题下的映射**

```kotlin
package com.zqc.tvnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainTabThemeTest {

    @Test
    fun mainTabTitlesFollowXianxiaAndModernThemes() {
        assertEquals("灵台总览", Theme.XIUXIAN.mainTabTitle(MainTab.OVERVIEW))
        assertEquals("演法调阵", Theme.XIUXIAN.mainTabTitle(MainTab.PRACTICE))
        assertEquals("符钥天机", Theme.XIUXIAN.mainTabTitle(MainTab.SYSTEM))

        assertEquals("总览与互联", Theme.DEFAULT.mainTabTitle(MainTab.OVERVIEW))
        assertEquals("演练与调控", Theme.DEFAULT.mainTabTitle(MainTab.PRACTICE))
        assertEquals("系统与运维", Theme.DEFAULT.mainTabTitle(MainTab.SYSTEM))
    }

    @Test
    fun sectionTitlesAreNonEmptyAcrossAllThemes() {
        for (theme in Theme.entries) {
            for (tab in MainTab.entries) {
                assertTrue(theme.mainTabTitle(tab).isNotBlank())
            }
            assertTrue(theme.quickActionsTitle.isNotBlank())
            assertTrue(theme.practiceCardTitle.isNotBlank())
            assertTrue(theme.controlCardTitle.isNotBlank())
            assertTrue(theme.systemCardTitle.isNotBlank())
            assertTrue(theme.keepAliveCardTitle.isNotBlank())
        }
    }
}
```

- [ ] **Step 2: 在 `Theme.kt` 中实现 `MainTab` 枚举与对应双语文案属性**

在 `Theme.kt` 顶部或末尾定义 `enum class MainTab { OVERVIEW, PRACTICE, SYSTEM }`，并在 `Theme` 中添加：
- `fun mainTabTitle(tab: MainTab): String`：
  - `OVERVIEW`: `if (isXianxia) "灵台总览" else "总览与互联"`
  - `PRACTICE`: `if (isXianxia) "演法调阵" else "演练与调控"`
  - `SYSTEM`: `if (isXianxia) "符钥天机" else "系统与运维"`
- `val quickActionsTitle: String get() = if (isXianxia) "快捷入口 · 灵音与试炼" else "快捷入口 · 音乐与测试"`
- `val practiceCardTitle: String get() = if (isXianxia) "四大演法 · 横幅试炼" else "通知演练 · 效果预览"`
- `val practiceCardSubtitle: String get() = if (isXianxia) "一键在电视大屏上演练文字、画影、灵镜动画与灵音传诵" else "一键在电视端触发文字、图片、抽帧视频与 TTS 语音播报横幅"`
- `val practiceCardHint: String get() = if (isXianxia) "秘法提示：在「试传一简」上长按遥控器确认键，可额外触发 16:9 灵镜画卷演练。" else "操作提示：在「测试通知」按钮上长按遥控器确认键，可触发 16:9 多媒体画卷演示。"`
- `val controlCardTitle: String get() = if (isXianxia) "阵法调控 · 法相方位" else "横幅偏好 · 显示与声音"`
- `val controlCardSubtitle: String get() = if (isXianxia) "查阅最近 20 条传音简录，调控默认显形方位、闭关免扰与 8 大主题" else "回看最近 20 条历史通知，切换横幅弹出位置、静音免扰与 8 款主题风格"`
- `val controlCardHint: String get() = if (isXianxia) "当前法相：${fullName}\n提示：短按主题按钮可列表选款，长按可直接循环切换下一法相。" else "当前主题：${fullName}\n提示：短按主题按钮可打开列表选择，长按可快速循环切换下一主题。"`
- `val systemCardTitle: String get() = if (isXianxia) "符钥管理 · 破境溯源" else "接口管理 · 版本与关于"`
- `val systemCardSubtitle: String get() = if (isXianxia) "抄录或重铸传音符钥，感应新简破境飞升，查阅简牍溯源信息" else "复制或重置接口 Token，在线检查版本更新，查看项目仓库与致谢"`
- `val keepAliveCardTitle: String get() = if (isXianxia) "护山大阵 · 常驻与保活指南" else "后台常驻 · ADB 保活说明"`

- [ ] **Step 3: 提交 Task 1**

```bash
git add app/src/main/java/com/zqc/tvnotify/Theme.kt app/src/test/java/com/zqc/tvnotify/MainTabThemeTest.kt
git commit -m "feat: add MainTab enum and bilingual tab/card copy in Theme"
```

---

### Task 2: 重构 `MainActivity.kt` 为顶部 3 Tab 焦点即切双栏布局

**Files:**
- Modify: `app/src/main/java/com/zqc/tvnotify/MainActivity.kt`

**Interfaces:**
- Consumes: `MainTab` 及 `Theme` 中的新增文案属性（来自 Task 1）
- Produces:
  - 顶部主导航栏：`overviewTabButton`、`practiceTabButton`、`systemTabButton`
  - 三个页面容器：`overviewPage`、`practicePage`、`systemPage`
  - `selectMainTab(tab: MainTab)`：切换当前页面可见性与顶部主 Tab 按钮选中样式
  - `onBackPressed()`：遥控器返回键焦点回退与回首页逻辑

- [ ] **Step 1: 在 `MainActivity.kt` 中声明主 Tab 状态与视图引用，构建顶部主导航栏**

1. 新增成员变量：
   - `private var activeMainTab: MainTab = MainTab.OVERVIEW`
   - `private lateinit var overviewTabButton: Button`
   - `private lateinit var practiceTabButton: Button`
   - `private lateinit var systemTabButton: Button`
   - `private lateinit var overviewPage: View`
   - `private lateinit var practicePage: View`
   - `private lateinit var systemPage: View`
   - `private lateinit var quickTestButton: Button`
   - `private lateinit var quickHistoryButton: Button`
   - `private lateinit var musicButton: Button`
   - `private lateinit var controlHintView: TextView`
2. 在 `buildRootView()` 的 `headerBar` 下方构建顶部主导航栏 `mainTabBar`：
   - 为三个主 Tab 按钮分配固定 `View.generateViewId()`，锁定左右边界：
     - `overviewTabButton.nextFocusLeftId = overviewTabButton.id`
     - `systemTabButton.nextFocusRightId = systemTabButton.id`
   - 在 `buildMainTabButton(tab: MainTab)` 中：
     - `setOnFocusChangeListener`: 当 `hasFocus == true` 时，执行微缩放动画并立即调用 `selectMainTab(tab)`；当 `hasFocus == false` 时恢复 `1.0f` 缩放并刷新选中态背景。
     - `setOnClickListener`: 调用 `selectMainTab(tab)`。
   - 在 `createMainTabStateDrawable(isSelected: Boolean)` 中区分三种视觉状态：
     - `state_focused`: `roundedRect(dp(10).toFloat(), theme.accentColor, dp(2), Color.WHITE)`
     - 未聚焦但 `isSelected == true`: `roundedRect(dp(10).toFloat(), Color.argb(0x44, Color.red(theme.accentColor), Color.green(theme.accentColor), Color.blue(theme.accentColor)), dp(2), theme.accentColor)`
     - 未聚焦且 `isSelected == false`: `roundedRect(dp(10).toFloat(), theme.cardBg, dp(1), theme.cardStroke)`

- [ ] **Step 2: 拆分并构建 `overviewPage`、`practicePage`、`systemPage` 三个双栏页面**

1. **`buildOverviewPage(isWideScreen: Boolean): View`（Tab 1 · 总览与互联）**：
   - 左栏（权重 `1.28f`）：
     - `statusCard`（2×2 状态指标网格：`stateMetricValue`、`portMetricValue`、`overlayMetricValue`、`ipMetricValue`）
     - `urlCard`（`callInfoTitleView`、`tokenValue`、`urlValue`）
     - `quickCard`（标题 `theme.quickActionsTitle`，下设单排 3 个按钮：`musicButton = buildButton("Sendspin 音乐") { ... }`、`quickTestButton = buildButton(theme.testButtonText, isPrimary = true, ...)`、`quickHistoryButton = buildButton(theme.historyButtonText) { ... }`）
   - 右栏（权重 `0.92f`）：
     - `qrCard`（保留原有 `haTabButton`、`tokenTabButton`、`qrTitleView`、`qrSubtitleView`、`qrImage`、`qrCaptionView`）
2. **`buildPracticePage(isWideScreen: Boolean): View`（Tab 2 · 演练与调控）**：
   - 左栏（权重 `1.0f`）：`practiceCard`
     - 标题 `theme.practiceCardTitle`（16sp 加粗白字）+ 副标题 `theme.practiceCardSubtitle`（12sp `theme.hintColor`）
     - 2×2 演练按钮网格：
       - 第一行：`testButton`（`isPrimary = true`，支持短按与长按）、`imageTestButton`
       - 第二行：`videoTestButton`、`ttsTestButton`
     - 底部提示框：`theme.practiceCardHint`
   - 右栏（权重 `1.0f`）：`controlCard`
     - 标题 `theme.controlCardTitle`（16sp 加粗白字）+ 副标题 `theme.controlCardSubtitle`（12sp `theme.hintColor`）
     - 2×2 调控按钮网格：
       - 第一行：`historyButton`、`positionButton`
       - 第二行：`muteButton`、`themeButton`
     - 底部提示框：`controlHintView`（显示 `theme.controlCardHint`）
3. **`buildSystemPage(isWideScreen: Boolean): View`（Tab 3 · 系统与运维）**：
   - 左栏（权重 `1.0f`）：`systemCard`
     - 标题 `theme.systemCardTitle`（16sp 加粗白字）+ 副标题 `theme.systemCardSubtitle`（12sp `theme.hintColor`）
     - 2×2 系统按钮网格：
       - 第一行：`copyButton`、`resetButton`
       - 第二行：`updateButton`、`aboutButton`
   - 右栏（权重 `1.0f`）：`keepAliveCard`
     - 标题 `theme.keepAliveCardTitle`（16sp 加粗白字）
     - 正文 `footerView`（显示 `theme.footerHint`）
4. 将三个页面放入 `FrameLayout` 内容容器 `contentContainer`，根据 `activeMainTab` 初始化各自的 `visibility` 与顶部主 Tab 样式。

- [ ] **Step 3: 完善焦点流转、状态刷新与遥控器返回键处理**

1. `onCreate`: 默认焦点置于 `overviewTabButton.requestFocus()`（确保一进主页即可左右切 Tab 或按下进入总览页）。
2. `selectMainTab(tab: MainTab)`:
   - 更新 `activeMainTab = tab`
   - 设置 `overviewPage.visibility`、`practicePage.visibility`、`systemPage.visibility`
   - 调用 `updateMainTabButtonStyles()` 更新三个主 Tab 按钮的文字颜色与 `StateListDrawable` 背景。
3. `updateStateAndQr`: 同步更新 `controlHintView.text = theme.controlCardHint`。
4. `onBackPressed()`:
   - 检查当前焦点是否已在三个顶部主 Tab 按钮之一（`overviewTabButton.hasFocus() || practiceTabButton.hasFocus() || systemTabButton.hasFocus()`）：
     - 若不在顶部主 Tab 栏：将焦点移回当前 `activeMainTab` 对应的顶部主 Tab 按钮并返回；
     - 若已在顶部主 Tab 栏且 `activeMainTab != MainTab.OVERVIEW`：调用 `overviewTabButton.requestFocus()` 切回首屏 Tab 并返回；
     - 否则调用 `super.onBackPressed()` 正常退出。

- [ ] **Step 4: 验证并提交 Task 2**

Run: `node --test app/src/test/js/sendspin-console.test.cjs`
Expected: PASS (7/7)

```bash
git add app/src/main/java/com/zqc/tvnotify/MainActivity.kt docs/superpowers/specs/2026-11-11-main-home-tabs-design.md docs/superpowers/plans/2026-11-11-main-home-tabs.md
git commit -m "feat: refactor MainActivity into 3 focus-switchable top tabs"
```
