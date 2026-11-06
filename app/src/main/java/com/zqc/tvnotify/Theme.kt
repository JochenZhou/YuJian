package com.zqc.tvnotify

import android.graphics.Color
import android.graphics.Typeface

/**
 * 8 大主题定义：
 * 1. [XIUXIAN]   ：🔮 修仙玉简（默认首选，青羊脂灵玉 + 仙家玉磬音）
 * 2. [DEFAULT]   ：🍎 tvOS 极简（苹果暗黑毛玻璃 + 柔和三和弦）
 * 3. [PALACE]    ：🏮 大唐鎏金（玄黑朱漆 + 赤金流光 + 宫廷青铜编钟）
 * 4. [CYBERPUNK] ：⚡ 赛博霓虹（夜之城紫黑 + 霓虹青黄 + 科幻 HUD 电子脉冲）
 * 5. [WUKONG]    ：🐵 黑神话·暗金（焦墨石板灰 + 古铜暗金 + 古刹金钵低鸣）
 * 6. [MATRIX]    ：💻 极客终端（纯黑 CRT + 荧光终端绿 + 复古双脉冲蜂鸣）
 * 7. [INK_LIGHT] ：🍵 江南宣纸（仿古宣纸米白横幅 + 松烟墨字 + 空谷清竹水滴音）
 * 8. [GAMER]     ：🏆 主机奖杯（PS5 深蓝流光 + 白金奖杯 + 解锁奖杯升调琶音）
 */
enum class Theme(
    val storageId: String,
    val emoji: String,
    val shortName: String,
    val fullName: String,
    val soundDesc: String
) {
    XIUXIAN(
        storageId = "xiuxian",
        emoji = "🔮",
        shortName = "修仙玉简",
        fullName = "🔮 修仙玉简（青羊脂玉 · 仙家玉磬音）",
        soundDesc = "仙家空灵玉磬音"
    ),
    DEFAULT(
        storageId = "default",
        emoji = "🍎",
        shortName = "tvOS 极简",
        fullName = "🍎 tvOS 极简（暗黑毛玻璃 · 柔和三和弦）",
        soundDesc = "tvOS 柔和三和弦"
    ),
    PALACE(
        storageId = "palace",
        emoji = "🏮",
        shortName = "大唐鎏金",
        fullName = "🏮 大唐鎏金（朱漆赤金 · 宫廷青铜编钟）",
        soundDesc = "宫廷青铜编钟音"
    ),
    CYBERPUNK(
        storageId = "cyberpunk",
        emoji = "⚡",
        shortName = "赛博霓虹",
        fullName = "⚡ 赛博霓虹（夜之城光效 · 科幻 HUD 脉冲音）",
        soundDesc = "科幻 HUD 电子扫频音"
    ),
    WUKONG(
        storageId = "wukong",
        emoji = "🐵",
        shortName = "黑神话·暗金",
        fullName = "🐵 黑神话·暗金（焦墨古铜 · 古刹金钵低鸣）",
        soundDesc = "古刹金钵低鸣音"
    ),
    MATRIX(
        storageId = "matrix",
        emoji = "💻",
        shortName = "极客终端",
        fullName = "💻 极客终端（黑客荧光绿 · CRT 终端蜂鸣音）",
        soundDesc = "复古 CRT 双脉冲蜂鸣"
    ),
    INK_LIGHT(
        storageId = "ink_light",
        emoji = "🍵",
        shortName = "江南宣纸",
        fullName = "🍵 江南宣纸（雅白宣纸 · 空谷清竹水滴音）",
        soundDesc = "空谷清竹水滴音"
    ),
    GAMER(
        storageId = "gamer",
        emoji = "🏆",
        shortName = "主机奖杯",
        fullName = "🏆 主机奖杯（白金流光 · 奖杯解锁升调琶音）",
        soundDesc = "解锁奖杯升调琶音"
    );

    companion object {
        /** 从 Prefs 读取主题；默认优先采用「修仙玉简」主题。 */
        fun current(context: android.content.Context): Theme {
            val saved = Prefs.getThemeId(context)
            return fromRaw(saved) ?: XIUXIAN
        }

        /** 解析主题标识（支持别名，未知返回 null）。 */
        fun fromRaw(raw: String?): Theme? {
            val key = raw?.trim()?.lowercase()?.replace("-", "_") ?: return null
            if (key.isEmpty() || key == "auto" || key == "global") return null
            return entries.firstOrNull { it.storageId == key } ?: when (key) {
                "tvos", "apple", "dark" -> DEFAULT
                "xianxia", "jade" -> XIUXIAN
                "tang", "royal", "gold" -> PALACE
                "cyber", "neon" -> CYBERPUNK
                "black_myth", "monkey", "dark_gold" -> WUKONG
                "terminal", "hacker", "geek" -> MATRIX
                "ink", "paper", "light", "xuan" -> INK_LIGHT
                "ps5", "trophy", "game" -> GAMER
                else -> null
            }
        }

        fun save(context: android.content.Context, theme: Theme) {
            Prefs.setThemeId(context, theme.storageId)
        }
    }

    /** 是否属于东方古风/国风语境（控制主界面古典或现代措辞） */
    val isXianxia: Boolean
        get() = this == XIUXIAN || this == PALACE || this == WUKONG || this == INK_LIGHT

    /** 横幅是否为浅色宣纸底色 */
    val isLightBanner: Boolean
        get() = this == INK_LIGHT

    // ------------------------------------------------------------------
    // 设置界面文案
    // ------------------------------------------------------------------

    val appTitle: String
        get() = when (this) {
            XIUXIAN -> "玉简 · 传音"
            PALACE -> "玉简 · 金殿圣谕"
            CYBERPUNK -> "YUJIAN // NET_HUD"
            WUKONG -> "玉简 · 天命金册"
            MATRIX -> "yujian@tv-node:~$"
            INK_LIGHT -> "玉简 · 江南尺素"
            GAMER -> "玉简 · 奖杯中枢"
            DEFAULT -> "玉简"
        }

    val appSubtitle: String
        get() = when (this) {
            XIUXIAN -> "灵讯横幅 · 常驻阵眼，一简传音"
            PALACE -> "金殿诏令 · 盛唐鎏金卷轴，八百里传讯"
            CYBERPUNK -> "NIGHT CITY OVERLAY · 神经链路全息通知终端"
            WUKONG -> "焦墨古金 · 灵台方寸，一念破万法"
            MATRIX -> "CRT TELEMETRY DAEMON · 局域网实时报文监控"
            INK_LIGHT -> "温润宣纸 · 尺素传情，清竹水滴入画"
            GAMER -> "TROPHY OVERLAY · 实时成就弹窗与家庭播报"
            DEFAULT -> "局域网通知横幅 · 电视端常驻服务"
        }

    val callInfoTitle: String get() = if (isXianxia) "传音玉简 · 符钥" else "本机调用信息"
    val qrTitle: String get() = if (isXianxia) "灵石符印 · 扫码即连" else "扫码获取调用地址"
    val qrSubtitle: String get() = if (isXianxia) "以手机或 Home Assistant 扫码，直接录入传音法诀" else "使用手机或外部系统扫码，导入通知配置"

    fun statusLine(running: Boolean, errorMsg: String? = null): String =
        if (isXianxia) {
            if (running) "阵法：已开启"
            else if (!errorMsg.isNullOrBlank()) "阵法：灵光未聚（$errorMsg）"
            else "阵法：灵光未聚（已尝试启动，请检查自启动权限）"
        } else {
            if (running) "服务状态：运行中"
            else if (!errorMsg.isNullOrBlank()) "服务状态：启动失败（$errorMsg）"
            else "服务状态：未运行（已尝试启动，请检查自启动权限）"
        }

    fun portLine(port: Int, running: Boolean): String =
        if (isXianxia) {
            "传音端口：$port（${if (running) "灵脉通畅" else "静待开启"}）"
        } else {
            "监听端口：$port（${if (running) "监听中" else "等待监听"}）"
        }

    fun overlayLine(granted: Boolean): String =
        if (isXianxia) {
            if (granted) "显形之术：已授予"
            else "显形之术：未授予（横幅无法显形，请点「试传一简」授权）"
        } else {
            if (granted) "悬浮窗权限：已授予"
            else "悬浮窗权限：未授予（横幅无法显示，请点「测试通知」授权）"
        }

    fun ipLine(ip: String, usable: Boolean): String =
        if (isXianxia) {
            if (usable) "本机方位（IPv4）：$ip" else "本机方位（IPv4）：未寻得灵网方位，请检查 Wi-Fi 连接"
        } else {
            if (usable) "本机 IPv4：$ip" else "本机 IPv4：未获取到局域网地址，请检查 Wi-Fi 连接"
        }

    fun tokenLabel(token: String): String =
        if (isXianxia) "符钥（Token）：$token" else "Token：$token"

    val testButtonText: String get() = if (isXianxia) "试传一简" else "测试通知"
    val copyButtonText: String get() = if (isXianxia) "抄录法诀" else "复制调用地址"
    val resetButtonText: String get() = if (isXianxia) "重铸符钥" else "重置 Token"
    val imageTestButtonText: String get() = if (isXianxia) "试显画影" else "图片测试"
    val videoTestButtonText: String get() = if (isXianxia) "试演灵镜" else "视频测试"
    val ttsTestButtonText: String get() = if (isXianxia) "灵音试诵" else "语音播报"
    val historyButtonText: String get() = if (isXianxia) "传音简录" else "通知历史"
    val aboutButtonText: String get() = if (isXianxia) "简牍溯源" else "关于"
    val updateButtonText: String get() = if (isXianxia) "破境飞升" else "检查更新"
    fun updateAvailableButtonText(version: String): String =
        if (isXianxia) "⚡ 破境 $version" else "⚡ 升级 $version"
    val checkingUpdateToast: String get() = if (isXianxia) "正在感应天机…" else "正在检查更新…"
    fun alreadyLatestToast(version: String): String =
        if (isXianxia) "当前已是最高境界（v$version）" else "当前已是最新版本（v$version）"
    val updateCheckFailToast: String get() =
        if (isXianxia) "天机晦涩，暂未感应到新简，请稍后重试" else "检查更新失败，请稍后重试"
    fun updateDialogTitle(version: String): String =
        if (isXianxia) "感应到飞升契机（$version）" else "发现新版本（$version）"
    val updateDialogAction: String get() = if (isXianxia) "立即破境" else "立即升级"
    val updateDialogCancel: String get() = if (isXianxia) "稍后再议" else "稍后再说"
    val downloadingUpdateTitle: String get() = if (isXianxia) "正在凝练新简…" else "正在下载新版安装包…"
    val downloadFailToast: String get() = if (isXianxia) "新简凝练受阻，请检查灵网" else "安装包下载失败，请检查网络"

    fun positionDisplayName(rawPosition: String): String = when (rawPosition.lowercase()) {
        "top" -> if (isXianxia) "顶部天幕" else "顶部居中"
        "top-right" -> "右上画中画"
        "bottom" -> if (isXianxia) "底部地煞" else "底部居中"
        "bottom-right" -> "右下画中画"
        "top-left" -> "左上画中画"
        "bottom-left" -> "左下画中画"
        else -> if (isXianxia) "顶部天幕" else "顶部居中"
    }

    fun positionButtonText(rawPosition: String): String =
        (if (isXianxia) "方位：" else "位置：") + positionDisplayName(rawPosition)

    fun muteButtonText(isMuted: Boolean): String = if (isMuted) {
        if (isXianxia) "闭关免扰 (静音)" else "静音模式 (开启)"
    } else {
        if (isXianxia) "灵音畅通 (发声)" else "提示声音 (开启)"
    }

    val overlayPermissionToast: String get() =
        if (isXianxia) "施法前请先授予「显示在其他应用上层」之权" else "请先允许「显示在其他应用上层」权限"
    val overlayPermissionFallbackToast: String get() =
        if (isXianxia) "请入系统设置亲手授予「显示在其他应用上层」" else "请在系统设置中手动允许「显示在其他应用上层」"
    val urlNotReadyToast: String get() = if (isXianxia) "法诀尚未铸成" else "地址尚未就绪"
    fun copiedToast(url: String): String =
        if (isXianxia) "法诀已录入剪贴：$url" else "已复制：$url"
    fun tokenResetToast(token: String): String =
        if (isXianxia) "符钥已重铸：$token" else "Token 已重置：$token"

    val clipLabel: String get() = if (isXianxia) "玉简 传音法诀" else "玉简 调用地址"

    /** 空标题通知时的默认兜底标题 */
    val defaultBannerTitle: String
        get() = when (this) {
            XIUXIAN -> "传音灵讯"
            PALACE -> "金殿诏令"
            CYBERPUNK -> "INCOMING // TRANSMISSION"
            WUKONG -> "灵台感应"
            MATRIX -> "SYS_NOTIFY"
            INK_LIGHT -> "尺素书函"
            GAMER -> "解锁新动态"
            DEFAULT -> "系统通知"
        }

    val testBannerTitle: String
        get() = when (this) {
            XIUXIAN -> "玉简传音"
            PALACE -> "大唐金殿 · 圣谕"
            CYBERPUNK -> "NIGHT CITY // 神经链路接入"
            WUKONG -> "黑神话 · 天命感应"
            MATRIX -> "root@tv-node: 链路就绪"
            INK_LIGHT -> "江南竹韵 · 尺素传书"
            GAMER -> "🏆 已解锁奖杯：千里传音"
            DEFAULT -> "演示通知"
        }

    val testBannerText: String
        get() = when (this) {
            XIUXIAN -> "此为试传之简，外部阵法（Home Assistant）亦可借此传音入室。"
            PALACE -> "金钟齐鸣，诏令已达。外部系统可随时呈递八百里加急奏报。"
            CYBERPUNK -> "全息浮层校准完毕 [STATUS: ONLINE]，随时接收局域网数据流。"
            WUKONG -> "古刹金钵低鸣，灵山讯息已化入画卷，静候天命人垂览。"
            MATRIX -> "[200 OK] Telemetry stream bound on port 8989. Awaiting payload..."
            INK_LIGHT -> "清泉映竹，翰墨留香。温润宣纸横幅已在日间模式下从容铺展。"
            GAMER -> "达成白金级智能家居联动成就！所有局域网推送将以奖杯弹窗呈现。"
            DEFAULT -> "这是一条来自玉简的测试横幅，Home Assistant 也可以这样推送。"
        }

    val footerHint: String get() =
        if (isXianxia) {
            "提示：重启后阵法自起；推荐配合开源桌面 Aerial 的 ADB 保活守护，或执行：\n" +
                "adb shell am startservice -n com.zqc.tvnotify/.NotifyService"
        } else {
            "提示：重启后服务自启；推荐配合开源桌面 Aerial 的 ADB 保活守护，或执行：\n" +
                "adb shell am startservice -n com.zqc.tvnotify/.NotifyService"
        }

    // ------------------------------------------------------------------
    // 前台服务通知（常驻栏）
    // ------------------------------------------------------------------

    val foregroundTitle: String get() = if (isXianxia) "玉简 · 传音阵运转中" else "玉简 运行中"

    fun foregroundText(port: Int): String =
        if (isXianxia) "传音阵眼监听端口 $port" else "局域网通知接口监听端口 $port"
    val foregroundChannelName: String get() = if (isXianxia) "玉简 · 常驻阵法" else "玉简 常驻服务"

    // ------------------------------------------------------------------
    // 设置主界面配色系统
    // ------------------------------------------------------------------

    val windowBg: Int
        get() = when (this) {
            XIUXIAN -> Color.parseColor("#FF0A1315")
            DEFAULT -> Color.parseColor("#FF101114")
            PALACE -> Color.parseColor("#FF160A0B")
            CYBERPUNK -> Color.parseColor("#FF0B0716")
            WUKONG -> Color.parseColor("#FF11100E")
            MATRIX -> Color.parseColor("#FF040A06")
            INK_LIGHT -> Color.parseColor("#FF1A1815") // 徽墨宣砚深底，衬托主界面白字
            GAMER -> Color.parseColor("#FF080E1C")
        }

    val cardBg: Int
        get() = when (this) {
            XIUXIAN -> Color.parseColor("#FF102024")
            DEFAULT -> Color.parseColor("#FF1A1C20")
            PALACE -> Color.parseColor("#FF241113")
            CYBERPUNK -> Color.parseColor("#FF150E2A")
            WUKONG -> Color.parseColor("#FF1B1916")
            MATRIX -> Color.parseColor("#FF08140C")
            INK_LIGHT -> Color.parseColor("#FF25221D")
            GAMER -> Color.parseColor("#FF101B35")
        }

    val cardStroke: Int
        get() = when (this) {
            XIUXIAN -> Color.parseColor("#FF1B383F")
            DEFAULT -> Color.parseColor("#FF2A2D33")
            PALACE -> Color.parseColor("#FF4A281E")
            CYBERPUNK -> Color.parseColor("#FF2E1E5A")
            WUKONG -> Color.parseColor("#FF332D24")
            MATRIX -> Color.parseColor("#FF12381E")
            INK_LIGHT -> Color.parseColor("#FF3D372E")
            GAMER -> Color.parseColor("#FF1F3463")
        }

    val hintColor: Int
        get() = when (this) {
            XIUXIAN -> Color.parseColor("#FF8AA8A4")
            DEFAULT -> Color.parseColor("#FF9A9AA2")
            PALACE -> Color.parseColor("#FFC4A48C")
            CYBERPUNK -> Color.parseColor("#FF9E92C8")
            WUKONG -> Color.parseColor("#FFA69E8E")
            MATRIX -> Color.parseColor("#FF62B582")
            INK_LIGHT -> Color.parseColor("#FFB5ADA0")
            GAMER -> Color.parseColor("#FF8FA6CC")
        }

    val accentColor: Int
        get() = when (this) {
            XIUXIAN -> Color.parseColor("#FF36D1A8")
            DEFAULT -> Color.parseColor("#FF4C9AFF")
            PALACE -> Color.parseColor("#FFE6B450")
            CYBERPUNK -> Color.parseColor("#FF00F0FF")
            WUKONG -> Color.parseColor("#FFC8A668")
            MATRIX -> Color.parseColor("#FF00FF66")
            INK_LIGHT -> Color.parseColor("#FFD95338") // 朱砂印泥红
            GAMER -> Color.parseColor("#FF3D8BFF")
        }

    val buttonBg: Int
        get() = when (this) {
            XIUXIAN -> Color.parseColor("#FF183037")
            DEFAULT -> Color.parseColor("#FF2A2C31")
            PALACE -> Color.parseColor("#FF33181A")
            CYBERPUNK -> Color.parseColor("#FF21153F")
            WUKONG -> Color.parseColor("#FF26231E")
            MATRIX -> Color.parseColor("#FF0D2114")
            INK_LIGHT -> Color.parseColor("#FF302C25")
            GAMER -> Color.parseColor("#FF17264A")
        }

    val buttonStroke: Int
        get() = when (this) {
            XIUXIAN -> Color.parseColor("#FF244953")
            DEFAULT -> Color.parseColor("#FF3A3D45")
            PALACE -> Color.parseColor("#FF633928")
            CYBERPUNK -> Color.parseColor("#FF3C2773")
            WUKONG -> Color.parseColor("#FF453C2F")
            MATRIX -> Color.parseColor("#FF1B4F2B")
            INK_LIGHT -> Color.parseColor("#FF4A4338")
            GAMER -> Color.parseColor("#FF2B4682")
        }

    // ------------------------------------------------------------------
    // 悬浮横幅配色与字体系统
    // ------------------------------------------------------------------

    val bannerBackgroundStart: Int
        get() = when (this) {
            XIUXIAN -> Color.argb(0xF4, 0x16, 0x36, 0x3C)
            DEFAULT -> Color.argb(0xEB, 0x22, 0x22, 0x26)
            PALACE -> Color.argb(0xF6, 0x28, 0x10, 0x12)    // 朱漆玄黑上渐变
            CYBERPUNK -> Color.argb(0xF4, 0x14, 0x0B, 0x29) // 夜之城深紫黑
            WUKONG -> Color.argb(0xF6, 0x1F, 0x1D, 0x19)    // 焦墨石板灰
            MATRIX -> Color.argb(0xF5, 0x05, 0x12, 0x09)    // CRT 磷光暗黑
            INK_LIGHT -> Color.argb(0xF8, 0xF7, 0xF3, 0xE8) // 仿古温润宣纸白
            GAMER -> Color.argb(0xF4, 0x10, 0x1E, 0x3C)     // PS5 流光深蓝
        }

    val bannerBackgroundEnd: Int
        get() = when (this) {
            XIUXIAN -> Color.argb(0xF8, 0x0C, 0x1B, 0x1E)
            DEFAULT -> Color.argb(0xF2, 0x14, 0x14, 0x16)
            PALACE -> Color.argb(0xFA, 0x14, 0x07, 0x08)
            CYBERPUNK -> Color.argb(0xF8, 0x0A, 0x06, 0x16)
            WUKONG -> Color.argb(0xFA, 0x0F, 0x0E, 0x0C)
            MATRIX -> Color.argb(0xFA, 0x02, 0x08, 0x04)
            INK_LIGHT -> Color.argb(0xFA, 0xEB, 0xE4, 0xD3)
            GAMER -> Color.argb(0xF8, 0x07, 0x0D, 0x1C)
        }

    val bannerTitle: Int
        get() = when (this) {
            XIUXIAN -> Color.argb(0xFF, 0xF4, 0xFF, 0xFA)
            DEFAULT -> Color.WHITE
            PALACE -> Color.argb(0xFF, 0xFF, 0xF3, 0xD6)    // 鎏金象牙白
            CYBERPUNK -> Color.argb(0xFF, 0xFC, 0xEE, 0x09) // 赛博荧光黄标题
            WUKONG -> Color.argb(0xFF, 0xED, 0xE3, 0xCE)    // 古卷宣白
            MATRIX -> Color.argb(0xFF, 0x4D, 0xFF, 0x94)    // 荧光终端亮绿
            INK_LIGHT -> Color.argb(0xFF, 0x22, 0x1E, 0x1A) // 松烟浓墨黑
            GAMER -> Color.argb(0xFF, 0xF5, 0xF8, 0xFF)     // 白金亮白
        }

    val bannerBodyText: Int
        get() = when (this) {
            XIUXIAN -> Color.argb(0xFF, 0xDC, 0xF3, 0xEB)
            DEFAULT -> Color.argb(0xFF, 0xEB, 0xEB, 0xF5)
            PALACE -> Color.argb(0xFF, 0xF2, 0xDF, 0xBA)
            CYBERPUNK -> Color.argb(0xFF, 0xBA, 0xF7, 0xFF) // 霓虹冰青正文
            WUKONG -> Color.argb(0xFF, 0xCC, 0xC2, 0xB0)
            MATRIX -> Color.argb(0xFF, 0x00, 0xE6, 0x5C)
            INK_LIGHT -> Color.argb(0xFF, 0x45, 0x3F, 0x37) // 淡墨正文
            GAMER -> Color.argb(0xFF, 0xD0, 0xDE, 0xF7)
        }

    val bannerTitleTypeface: Typeface
        get() = when (this) {
            XIUXIAN, PALACE, WUKONG, INK_LIGHT -> Typeface.SERIF
            CYBERPUNK, MATRIX -> Typeface.MONOSPACE
            DEFAULT, GAMER -> Typeface.DEFAULT_BOLD
        }

    val bannerBodyTypeface: Typeface
        get() = when (this) {
            XIUXIAN, PALACE, WUKONG, INK_LIGHT -> Typeface.SERIF
            CYBERPUNK, MATRIX -> Typeface.MONOSPACE
            DEFAULT, GAMER -> Typeface.SANS_SERIF
        }

    val bannerTitleSerif: Boolean
        get() = bannerTitleTypeface === Typeface.SERIF

    val bannerCornerRadiusDp: Float
        get() = when (this) {
            XIUXIAN, PALACE, INK_LIGHT -> 20f
            CYBERPUNK, MATRIX -> 8f // 赛博与终端采用硬朗切角风格
            WUKONG -> 16f
            GAMER -> 22f // 主机胶囊圆角风
            DEFAULT -> 15f
        }

    fun levelBadgeText(level: BannerLevel): String = when (this) {
        XIUXIAN -> when (level) {
            BannerLevel.INFO -> "传音"
            BannerLevel.WARN -> "灵警"
            BannerLevel.ERROR -> "告急"
        }
        PALACE -> when (level) {
            BannerLevel.INFO -> "圣谕"
            BannerLevel.WARN -> "钦命"
            BannerLevel.ERROR -> "八百里加急"
        }
        CYBERPUNK -> when (level) {
            BannerLevel.INFO -> "NET_MSG"
            BannerLevel.WARN -> "SYS_WARN"
            BannerLevel.ERROR -> "BREACH"
        }
        WUKONG -> when (level) {
            BannerLevel.INFO -> "天命"
            BannerLevel.WARN -> "妖气"
            BannerLevel.ERROR -> "劫难"
        }
        MATRIX -> when (level) {
            BannerLevel.INFO -> "[OK]"
            BannerLevel.WARN -> "[WARN]"
            BannerLevel.ERROR -> "[FATAL]"
        }
        INK_LIGHT -> when (level) {
            BannerLevel.INFO -> "雅集"
            BannerLevel.WARN -> "留意"
            BannerLevel.ERROR -> "急函"
        }
        GAMER -> when (level) {
            BannerLevel.INFO -> "已解锁"
            BannerLevel.WARN -> "稀有成就"
            BannerLevel.ERROR -> "高难挑战"
        }
        DEFAULT -> when (level) {
            BannerLevel.INFO -> "通知"
            BannerLevel.WARN -> "警告"
            BannerLevel.ERROR -> "错误"
        }
    }

    fun bannerIconColor(level: BannerLevel): Int = when (this) {
        XIUXIAN -> when (level) {
            BannerLevel.INFO -> Color.rgb(0x63, 0xE6, 0xBE)
            BannerLevel.WARN -> Color.rgb(0xFF, 0xC0, 0x43)
            BannerLevel.ERROR -> Color.rgb(0xFF, 0x5C, 0x5C)
        }
        PALACE -> when (level) {
            BannerLevel.INFO -> Color.rgb(0xF0, 0xC2, 0x65) // 赤金
            BannerLevel.WARN -> Color.rgb(0xFF, 0x9E, 0x2C) // 琥珀金
            BannerLevel.ERROR -> Color.rgb(0xFF, 0x4D, 0x40) // 朱砂赤
        }
        CYBERPUNK -> when (level) {
            BannerLevel.INFO -> Color.rgb(0x00, 0xF0, 0xFF) // 霓虹青
            BannerLevel.WARN -> Color.rgb(0xFC, 0xEE, 0x09) // 赛博黄
            BannerLevel.ERROR -> Color.rgb(0xFF, 0x00, 0x55) // 故障品红
        }
        WUKONG -> when (level) {
            BannerLevel.INFO -> Color.rgb(0xD4, 0xB2, 0x72) // 古铜暗金
            BannerLevel.WARN -> Color.rgb(0xE8, 0x8E, 0x38) // 业火橙金
            BannerLevel.ERROR -> Color.rgb(0xDF, 0x46, 0x38) // 血煞红
        }
        MATRIX -> when (level) {
            BannerLevel.INFO -> Color.rgb(0x00, 0xFF, 0x66) // 终端荧光绿
            BannerLevel.WARN -> Color.rgb(0xCC, 0xFF, 0x00) // 荧光黄绿
            BannerLevel.ERROR -> Color.rgb(0xFF, 0x33, 0x33) // 终端报错红
        }
        INK_LIGHT -> when (level) {
            BannerLevel.INFO -> Color.rgb(0xC8, 0x3C, 0x23) // 朱砂印泥红
            BannerLevel.WARN -> Color.rgb(0xB8, 0x65, 0x14) // 赭石黄
            BannerLevel.ERROR -> Color.rgb(0xA8, 0x18, 0x18) // 浓朱砂
        }
        GAMER -> when (level) {
            BannerLevel.INFO -> Color.rgb(0xF5, 0xC8, 0x4C) // 黄金奖杯色
            BannerLevel.WARN -> Color.rgb(0x52, 0xB6, 0xFF) // 白金蓝光
            BannerLevel.ERROR -> Color.rgb(0xFF, 0x55, 0x66) // 传奇赤红
        }
        DEFAULT -> when (level) {
            BannerLevel.INFO -> Color.WHITE
            BannerLevel.WARN -> Color.rgb(0xFF, 0xB0, 0x20)
            BannerLevel.ERROR -> Color.rgb(0xFF, 0x45, 0x3A)
        }
    }

    fun bannerStrokeColor(level: BannerLevel): Int {
        val c = bannerIconColor(level)
        val alpha = when (this) {
            DEFAULT -> if (level == BannerLevel.INFO) 0x38 else 0x70
            INK_LIGHT -> 0x99
            CYBERPUNK, MATRIX -> 0xD8
            else -> 0xBD
        }
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
    }
}
