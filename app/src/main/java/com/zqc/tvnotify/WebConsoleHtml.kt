package com.zqc.tvnotify

/**
 * 玉简内置手机/电脑 Web 控制台单页 HTML。
 * 纯内联 CSS + 原生 JavaScript，零外部 CDN 依赖，在局域网离线环境下 100% 可靠秒开。
 * 深度联动 9 大主题并针对手机窄屏单页长滚动进行了防溢出与触控对齐优化，支持双向实时同步切换。
 */
object WebConsoleHtml {

    fun render(
        token: String,
        version: String,
        ip: String,
        port: Int,
        isMuted: Boolean,
        defaultPosition: String,
        theme: Theme
    ): String {
        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
<title>玉简 · 控制台</title>
<style>
/* 修仙玉简主题（默认） */
:root, body[data-theme="xiuxian"] {
  --bg: #0a1315;
  --card-bg: #102024;
  --card-stroke: #1b383f;
  --text: #f4fffa;
  --text-muted: #8aa8a4;
  --accent: #36d1a8;
  --accent-glow: rgba(54, 209, 168, 0.25);
  --btn-bg: #183037;
  --btn-stroke: #244953;
  --danger: #ff5c5c;
  --warn: #ffc043;
}
/* 现代深色 tvOS 主题 */
body[data-theme="default"] {
  --bg: #101114;
  --card-bg: #1a1c20;
  --card-stroke: #2a2d33;
  --text: #f2f2f7;
  --text-muted: #9a9aa2;
  --accent: #4c9aff;
  --accent-glow: rgba(76, 154, 255, 0.25);
  --btn-bg: #2a2c31;
  --btn-stroke: #3a3d45;
  --danger: #ff453a;
  --warn: #ffb020;
}
/* 大唐鎏金 */
body[data-theme="palace"] {
  --bg: #160a0b;
  --card-bg: #241113;
  --card-stroke: #4a281e;
  --text: #fff3d6;
  --text-muted: #c4a48c;
  --accent: #e6b450;
  --accent-glow: rgba(230, 180, 80, 0.25);
  --btn-bg: #33181a;
  --btn-stroke: #633928;
  --danger: #ff4d40;
  --warn: #ff9e2c;
}
/* 赛博霓虹 */
body[data-theme="cyberpunk"] {
  --bg: #0b0716;
  --card-bg: #150e2a;
  --card-stroke: #2e1e5a;
  --text: #baf7ff;
  --text-muted: #9e92c8;
  --accent: #00f0ff;
  --accent-glow: rgba(0, 240, 255, 0.25);
  --btn-bg: #21153f;
  --btn-stroke: #3c2773;
  --danger: #ff0055;
  --warn: #fcee09;
}
/* 黑神话·暗金 */
body[data-theme="wukong"] {
  --bg: #11100e;
  --card-bg: #1b1916;
  --card-stroke: #332d24;
  --text: #ede3ce;
  --text-muted: #a69e8e;
  --accent: #c8a668;
  --accent-glow: rgba(200, 166, 104, 0.25);
  --btn-bg: #26231e;
  --btn-stroke: #453c2f;
  --danger: #df4638;
  --warn: #e88e38;
}
/* 极客终端 */
body[data-theme="matrix"] {
  --bg: #040a06;
  --card-bg: #08140c;
  --card-stroke: #12381e;
  --text: #4dff94;
  --text-muted: #62b582;
  --accent: #00ff66;
  --accent-glow: rgba(0, 255, 102, 0.25);
  --btn-bg: #0d2114;
  --btn-stroke: #1b4f2b;
  --danger: #ff3333;
  --warn: #ccff00;
}
/* 江南宣纸 */
body[data-theme="ink_light"] {
  --bg: #1a1815;
  --card-bg: #25221d;
  --card-stroke: #3d372e;
  --text: #f5f2e9;
  --text-muted: #b5ada0;
  --accent: #d95338;
  --accent-glow: rgba(217, 83, 56, 0.25);
  --btn-bg: #302c25;
  --btn-stroke: #4a4338;
  --danger: #ff453a;
  --warn: #e69b28;
}
/* 主机奖杯 */
body[data-theme="gamer"] {
  --bg: #080e1c;
  --card-bg: #101b35;
  --card-stroke: #1f3463;
  --text: #f5f8ff;
  --text-muted: #8fa6cc;
  --accent: #3d8bff;
  --accent-glow: rgba(61, 139, 255, 0.25);
  --btn-bg: #17264a;
  --btn-stroke: #2b4682;
  --danger: #ff5566;
  --warn: #f5c84c;
}
/* 方块世界（我的世界像素风） */
body[data-theme="minecraft"] {
  --bg: #121612;
  --card-bg: #1d221d;
  --card-stroke: #3b4739;
  --text: #f3f7ef;
  --text-muted: #a0b39a;
  --accent: #55cc44;
  --accent-glow: rgba(85, 204, 68, 0.25);
  --btn-bg: #2a3229;
  --btn-stroke: #4d5c49;
  --danger: #ff5555;
  --warn: #ffaa00;
}
body[data-theme="minecraft"] .brand h1,
body[data-theme="minecraft"] .card-title,
body[data-theme="minecraft"] button,
body[data-theme="minecraft"] .version-badge {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
body[data-theme="minecraft"] .card,
body[data-theme="minecraft"] .brand-icon,
body[data-theme="minecraft"] .status-pill,
body[data-theme="minecraft"] .version-badge,
body[data-theme="minecraft"] input,
body[data-theme="minecraft"] select,
body[data-theme="minecraft"] textarea,
body[data-theme="minecraft"] button,
body[data-theme="minecraft"] .checkbox-item,
body[data-theme="minecraft"] .details-box,
body[data-theme="minecraft"] .code-box,
body[data-theme="minecraft"] .history-btn,
body[data-theme="minecraft"] .copy-btn {
  border-radius: 2px;
  border-width: 2px;
}
* { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; }
html, body { max-width: 100%; overflow-x: hidden; }
body { background: var(--bg); color: var(--text); padding: 14px 12px calc(24px + env(safe-area-inset-bottom)); font-size: 14px; line-height: 1.5; -webkit-font-smoothing: antialiased; -webkit-text-size-adjust: 100%; transition: background 0.25s, color 0.25s; }
.container { width: 100%; max-width: 680px; margin: 0 auto; }
header { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; padding: 10px 0 16px; border-bottom: 1px solid var(--card-stroke); margin-bottom: 14px; }
.brand { display: flex; align-items: center; gap: 10px; min-width: 0; flex: 1 1 auto; }
.brand > div { min-width: 0; }
.brand-icon { flex-shrink: 0; width: 34px; height: 34px; border-radius: 8px; background: var(--card-bg); border: 1px solid var(--accent); display: flex; align-items: center; justify-content: center; font-size: 18px; transition: border-color 0.25s; }
.brand h1 { font-size: 17px; font-weight: 700; color: #fff; letter-spacing: 0.4px; overflow-wrap: anywhere; }
.brand .sub { font-size: 11px; color: var(--text-muted); overflow-wrap: anywhere; }
.version-badge { flex-shrink: 0; font-size: 11px; color: var(--accent); background: var(--accent-glow); padding: 3px 8px; border-radius: 12px; border: 1px solid var(--accent); }
.status-pill { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 10px; max-width: 100%; font-size: 12px; color: var(--accent); background: rgba(54, 209, 168, 0.1); padding: 7px 12px; border-radius: 12px; border: 1px solid var(--card-stroke); margin-bottom: 14px; }
.status-dot { flex-shrink: 0; width: 8px; height: 8px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 8px var(--accent); }
.card { background: var(--card-bg); border: 1px solid var(--card-stroke); border-radius: 14px; padding: 15px; margin-bottom: 14px; box-shadow: 0 4px 16px rgba(0,0,0,0.25); transition: background 0.25s, border-color 0.25s; overflow: hidden; }
.card-title { font-size: 14px; font-weight: 600; color: var(--accent); margin-bottom: 12px; display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 6px 10px; }
.form-group { margin-bottom: 12px; min-width: 0; }
label { display: block; font-size: 12px; color: var(--text-muted); margin-bottom: 5px; overflow-wrap: anywhere; }
input[type="text"], input[type="number"], select, textarea {
  display: block; width: 100%; max-width: 100%; min-width: 0; min-height: 44px;
  background: rgba(0,0,0,0.35); border: 1px solid var(--card-stroke); color: #fff;
  border-radius: 8px; padding: 10px 12px; font-size: 16px; line-height: 1.35; outline: none; transition: border-color 0.2s;
}
select { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding-right: 28px; }
input:focus, select:focus, textarea:focus { border-color: var(--accent); box-shadow: 0 0 8px var(--accent-glow); }
input:disabled, select:disabled { opacity: 0.6; cursor: not-allowed; }
textarea { resize: vertical; min-height: 72px; }
.row { display: flex; flex-wrap: wrap; gap: 10px; }
.col { flex: 1 1 0; min-width: 0; }
.grid-2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; align-items: end; }
.grid-2 .form-group { margin-bottom: 0; }
.grid-params { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 12px; }
.grid-params .form-group { margin-bottom: 0; }
.checkbox-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin: 12px 0; }
.checkbox-item { display: flex; align-items: center; gap: 8px; min-width: 0; min-height: 44px; margin-bottom: 0; font-size: 13px; color: var(--text); background: rgba(0,0,0,0.2); padding: 8px 10px; border-radius: 8px; border: 1px solid var(--card-stroke); cursor: pointer; }
.checkbox-item span { min-width: 0; overflow-wrap: anywhere; }
.checkbox-item input { flex-shrink: 0; accent-color: var(--accent); width: 18px; height: 18px; cursor: pointer; }
button {
  min-height: 44px; padding: 10px 14px; border-radius: 8px; font-size: 14px; font-weight: 600; cursor: pointer;
  transition: all 0.2s; outline: none; border: 1px solid transparent; text-align: center;
  max-width: 100%; line-height: 1.3; white-space: normal; word-break: break-word;
}
.btn-primary { background: var(--accent); color: #0a1315; border-color: var(--accent); font-weight: 700; }
.btn-primary:active { opacity: 0.85; transform: scale(0.98); }
.btn-secondary { background: var(--btn-bg); color: #fff; border-color: var(--btn-stroke); }
.btn-secondary:active { background: var(--card-stroke); }
.btn-full { display: block; width: 100%; }
.btn-group { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 14px; }
.btn-group > button { flex: 1 1 140px; min-width: 0; }
.grid-2 > button { width: 100%; min-width: 0; }
.sendspin-controls { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin-top: 12px; }
.sendspin-controls > button { width: 100%; min-width: 0; padding: 10px 6px; font-size: 13px; }
.sync-status-box { font-size: 12.5px; color: var(--text-muted); margin-top: 10px; padding: 8px 10px; background: rgba(0,0,0,0.22); border: 1px solid var(--card-stroke); border-radius: 8px; overflow-wrap: anywhere; line-height: 1.45; }
.details-toggle { font-size: 12.5px; color: var(--accent); cursor: pointer; text-decoration: underline; margin-bottom: 10px; display: inline-block; padding: 4px 0; }
.details-box { display: none; background: rgba(0,0,0,0.2); border: 1px dashed var(--card-stroke); padding: 12px; border-radius: 8px; margin-bottom: 12px; }
.history-item { padding: 10px 2px; border-bottom: 1px solid var(--card-stroke); display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }
.history-item:last-child { border-bottom: none; }
.history-info { flex: 1 1 auto; min-width: 0; overflow-wrap: anywhere; }
.history-meta { font-size: 11px; color: var(--text-muted); display: flex; flex-wrap: wrap; gap: 4px 8px; align-items: center; margin-bottom: 4px; }
.history-level { font-size: 10px; padding: 1px 6px; border-radius: 4px; font-weight: bold; }
.level-info { background: rgba(54,209,168,0.2); color: var(--accent); }
.level-warn { background: rgba(255,192,67,0.2); color: var(--warn); }
.level-error { background: rgba(255,92,92,0.2); color: var(--danger); }
.history-title { font-weight: 600; color: #fff; font-size: 13.5px; overflow-wrap: anywhere; }
.history-text { font-size: 12.5px; color: var(--text-muted); margin-top: 2px; overflow-wrap: anywhere; }
.history-btn { flex-shrink: 0; width: auto; min-height: 36px; font-size: 12px; padding: 6px 12px; border-radius: 6px; background: var(--btn-bg); border: 1px solid var(--btn-stroke); color: #fff; cursor: pointer; white-space: nowrap; }
.code-box { background: rgba(0,0,0,0.45); border: 1px solid var(--card-stroke); border-radius: 8px; overflow: hidden; }
.code-header { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 12px; border-bottom: 1px solid var(--card-stroke); background: rgba(0,0,0,0.25); font-size: 11.5px; color: var(--text-muted); }
.copy-btn { flex-shrink: 0; min-height: 32px; font-size: 11.5px; padding: 4px 12px; background: var(--btn-bg); border: 1px solid var(--btn-stroke); color: var(--accent); border-radius: 6px; cursor: pointer; }
.code-box pre { padding: 10px 12px; margin: 0; font-family: monospace; font-size: 12px; line-height: 1.5; color: #b7e3d8; white-space: pre-wrap; overflow-wrap: anywhere; }
.toast { position: fixed; bottom: calc(18px + env(safe-area-inset-bottom)); left: 50%; transform: translateX(-50%); max-width: calc(100vw - 32px); text-align: center; background: rgba(16,32,36,0.95); border: 1px solid var(--accent); color: #fff; padding: 9px 16px; border-radius: 20px; font-size: 13px; z-index: 999; display: none; box-shadow: 0 4px 12px rgba(0,0,0,0.5); }
@media (max-width: 560px) {
  .grid-params { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .grid-params > .param-span-full { grid-column: 1 / -1; }
}
@media (max-width: 480px) {
  .grid-2 { grid-template-columns: minmax(0, 1fr); }
  .card { padding: 14px 12px; }
}
@media (max-width: 360px) {
  .checkbox-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>
</head>
<body data-theme="${theme.storageId}">
<div class="container">
  <header>
    <div class="brand">
      <div class="brand-icon" id="brand-icon">${theme.emoji}</div>
      <div>
        <h1 id="brand-title">${if (theme.isXianxia) "玉简 · 传音控制台" else "玉简 · 通知控制台"}</h1>
        <div class="sub" id="brand-sub">${if (theme.isXianxia) "灵讯横幅 · 电视大屏局域网传音" else "局域网通知横幅 · 电视大屏悬浮通知"}</div>
      </div>
    </div>
    <span class="version-badge">v$version</span>
  </header>

  <div class="status-pill">
    <div class="status-dot"></div>
    <span id="pill-online">阵法在线 · $ip:$port</span>
    <span id="pill-muted">· ${if (isMuted) (if (theme.isXianxia) "闭关免扰" else "静音模式") else (if (theme.isXianxia) "灵音畅通" else "提示声音")}</span>
    <span id="pill-pos">· 方位：$defaultPosition</span>
  </div>

  <!-- 发送通知卡片 -->
  <div class="card">
    <div class="card-title">
      <span id="card-send-title">${if (theme.isXianxia) "🚀 传音入室" else "📢 发送通知"}</span>
      <span style="font-size: 11.5px; color: var(--text-muted);">POST /{token}/notify</span>
    </div>
    <div class="form-group">
      <label id="label-title">主标题 (Title)</label>
      <input type="text" id="title" value="${theme.testBannerTitle}" placeholder="例如：门铃感应 / 洗衣机提醒">
    </div>
    <div class="form-group">
      <label id="label-text">正文内容 (Text)</label>
      <textarea id="text" placeholder="输入推送文案内容...">${theme.testBannerText}</textarea>
    </div>
    <div class="grid-params">
      <div class="form-group">
        <label id="label-level">分级着色 (Level)</label>
        <select id="level">
          <option value="info" selected id="opt-info">${if (theme.isXianxia) "传音 (Info)" else "普通 (Info)"}</option>
          <option value="warn" id="opt-warn">${if (theme.isXianxia) "灵警 (Warn)" else "警告 (Warn)"}</option>
          <option value="error" id="opt-error">${if (theme.isXianxia) "告急 (Error)" else "紧急 (Error)"}</option>
        </select>
      </div>
      <div class="form-group">
        <label id="label-pos">显形方位 (Position)</label>
        <select id="position">
          <option value="" selected>默认 (跟随电视设置)</option>
          <option value="top">顶部居中 (Top)</option>
          <option value="top-right">右上角画中画 (Top-Right PiP)</option>
          <option value="bottom">底部居中 (Bottom)</option>
          <option value="bottom-right">右下角画中画 (Bottom-Right PiP)</option>
          <option value="top-left">左上角画中画 (Top-Left)</option>
          <option value="bottom-left">左下角画中画 (Bottom-Left)</option>
        </select>
      </div>
      <div class="form-group param-span-full">
        <label for="duration">显示时长 (秒)</label>
        <input type="number" id="duration" min="1" max="30" value="5">
      </div>
    </div>
    <div class="form-group">
      <label>单次通知专属主题与音效 (Theme，可选覆盖)</label>
      <select id="req-theme">
        <option value="" selected>跟随电视全局主题（当前：${theme.emoji} ${theme.shortName}）</option>
        <option value="xiuxian">🔮 修仙玉简（青羊脂玉 · 仙家玉磬音）</option>
        <option value="default">🍎 tvOS 极简（暗黑毛玻璃 · 柔和三和弦）</option>
        <option value="palace">🏮 大唐鎏金（朱漆赤金 · 宫廷青铜编钟）</option>
        <option value="cyberpunk">⚡ 赛博霓虹（夜之城光效 · 科幻 HUD 脉冲音）</option>
        <option value="wukong">🐵 黑神话·暗金（焦墨古铜 · 古刹金钵低鸣）</option>
        <option value="matrix">💻 极客终端（黑客荧光绿 · CRT 终端蜂鸣音）</option>
        <option value="ink_light">🍵 江南宣纸（雅白宣纸 · 空谷清竹水滴音）</option>
        <option value="gamer">🏆 主机奖杯（白金流光 · 奖杯解锁升调琶音）</option>
        <option value="minecraft">🧱 方块世界（草方块像素 · 8-bit 经验球音）</option>
      </select>
    </div>

    <div class="checkbox-grid">
      <label class="checkbox-item"><input type="checkbox" id="sound" checked> <span id="chk-sound-text">提示音效 (Sound)</span></label>
      <label class="checkbox-item"><input type="checkbox" id="tts" checked> <span id="chk-tts-text">原生 TTS 语音播报</span></label>
      <label class="checkbox-item"><input type="checkbox" id="sticky"> <span id="chk-sticky-text">常驻停留 (Sticky)</span></label>
      <label class="checkbox-item"><input type="checkbox" id="queue"> <span id="chk-queue-text">排队顺延 (Queue)</span></label>
    </div>

    <span class="details-toggle" onclick="toggleDetails()">⚙️ 展开更多多媒体与标识配置...</span>
    <div class="details-box" id="details-box">
      <div class="form-group">
        <label>静态图片或快照 URL (image)</label>
        <input type="text" id="image" placeholder="http://.../snapshot.jpg">
      </div>
      <div class="form-group">
        <label>快照定时轮询刷新间隔 (毫秒，例如 1000 即 1 秒准视频流)</label>
        <input type="number" id="image_interval" placeholder="0 (不轮询)">
      </div>
      <div class="form-group">
        <label>动态 MJPEG 视频流 URL (video)</label>
        <input type="text" id="video" placeholder="http://.../mjpeg.cgi">
      </div>
      <div class="form-group">
        <label>通知唯一 ID (id，相同时原地无闪烁刷新)</label>
        <input type="text" id="id" placeholder="例如：nas_download_task">
      </div>
    </div>

    <div class="btn-group">
      <button class="btn-primary" id="btn-send" onclick="sendNotify()">${if (theme.isXianxia) "🚀 立即传音" else "🚀 立即发送"}</button>
      <button class="btn-secondary" id="btn-dismiss" onclick="dismissBanner()">${if (theme.isXianxia) "🧹 消散当前横幅" else "🧹 关闭当前横幅"}</button>
    </div>
  </div>

  <!-- 电视控制与阵法设置 -->
  <div class="card">
    <div class="card-title">
      <span id="card-control-title">${if (theme.isXianxia) "🎛️ 电视阵法调控" else "🎛️ 电视服务调控"}</span>
      <span style="font-size: 11.5px; color: var(--text-muted);">实时下发配置</span>
    </div>
    <div class="grid-2" style="margin-bottom: 12px;">
      <div class="form-group">
        <label>声音开关</label>
        <button type="button" id="btn-toggle-mute" class="btn-secondary btn-full" onclick="toggleMute()">
          ${if (isMuted) (if (theme.isXianxia) "🔇 闭关免扰 (已开启静音)" else "🔇 静音模式 (已开启)") else (if (theme.isXianxia) "🔊 灵音畅通 (已开启声音)" else "🔊 提示音效 (已开启)")}
        </button>
      </div>
      <div class="form-group">
        <label for="config-pos">默认显示方位</label>
        <select id="config-pos" onchange="changeDefaultPosition()">
          <option value="top" ${if (defaultPosition == "top") "selected" else ""}>默认方位：顶部居中 (Top)</option>
          <option value="top-right" ${if (defaultPosition == "top-right") "selected" else ""}>默认方位：右上画中画 (Top-Right)</option>
          <option value="bottom" ${if (defaultPosition == "bottom") "selected" else ""}>默认方位：底部居中 (Bottom)</option>
          <option value="bottom-right" ${if (defaultPosition == "bottom-right") "selected" else ""}>默认方位：右下画中画 (Bottom-Right)</option>
          <option value="top-left" ${if (defaultPosition == "top-left") "selected" else ""}>默认方位：左上画中画 (Top-Left)</option>
          <option value="bottom-left" ${if (defaultPosition == "bottom-left") "selected" else ""}>默认方位：左下画中画 (Bottom-Left)</option>
        </select>
      </div>
    </div>
    <div class="form-group" style="margin-bottom: 0;">
      <label for="config-theme">全局默认主题与提示音</label>
      <select id="config-theme" onchange="changeGlobalTheme()">
        <option value="xiuxian" ${if (theme == Theme.XIUXIAN) "selected" else ""}>全局默认主题：🔮 修仙玉简（仙家玉磬音）</option>
        <option value="default" ${if (theme == Theme.DEFAULT) "selected" else ""}>全局默认主题：🍎 tvOS 极简（柔和三和弦）</option>
        <option value="palace" ${if (theme == Theme.PALACE) "selected" else ""}>全局默认主题：🏮 大唐鎏金（宫廷青铜编钟）</option>
        <option value="cyberpunk" ${if (theme == Theme.CYBERPUNK) "selected" else ""}>全局默认主题：⚡ 赛博霓虹（科幻 HUD 脉冲音）</option>
        <option value="wukong" ${if (theme == Theme.WUKONG) "selected" else ""}>全局默认主题：🐵 黑神话·暗金（古刹金钵低鸣）</option>
        <option value="matrix" ${if (theme == Theme.MATRIX) "selected" else ""}>全局默认主题：💻 极客终端（CRT 终端蜂鸣音）</option>
        <option value="ink_light" ${if (theme == Theme.INK_LIGHT) "selected" else ""}>全局默认主题：🍵 江南宣纸（空谷清竹水滴音）</option>
        <option value="gamer" ${if (theme == Theme.GAMER) "selected" else ""}>全局默认主题：🏆 主机奖杯（奖杯解锁升调琶音）</option>
        <option value="minecraft" ${if (theme == Theme.MINECRAFT) "selected" else ""}>全局默认主题：🧱 方块世界（8-bit 经验球音）</option>
      </select>
    </div>
  </div>

  <!-- Sendspin 音乐控制 -->
  <div class="card">
    <div class="card-title"><span>Sendspin 音乐</span><span id="sendspin-state" style="font-size:12px;color:var(--text-muted)">正在检查…</span></div>
    <div id="sendspin-track" style="font-size:17px;font-weight:600;overflow-wrap:anywhere">未播放</div>
    <div id="sendspin-artist" style="font-size:13px;color:var(--text-muted);margin-top:4px;overflow-wrap:anywhere"></div>
    <div class="sendspin-controls">
      <button type="button" class="btn-secondary" onclick="sendspinCommand('previous')">上一首</button>
      <button type="button" class="btn-primary" onclick="sendspinCommand('play_pause')">播放 / 暂停</button>
      <button type="button" class="btn-secondary" onclick="sendspinCommand('next')">下一首</button>
      <button type="button" class="btn-secondary" onclick="sendspinCommand('volume_down')">音量 −</button>
      <button type="button" class="btn-secondary" onclick="sendspinCommand('volume_up')">音量 +</button>
      <button type="button" class="btn-secondary" onclick="sendspinCommand('mute')">静音切换</button>
    </div>
    <div class="grid-2" style="margin-top:12px">
      <div class="form-group">
        <label for="sendspin-sync-mode">同步模式</label>
        <select id="sendspin-sync-mode">
          <option value="auto" selected>自动同步</option>
          <option value="manual">手动固定补偿</option>
        </select>
      </div>
      <div class="form-group">
        <label for="sendspin-delay">手动固定补偿 (0–5000 ms，自动模式保留)</label>
        <input id="sendspin-delay" type="number" min="0" max="5000" step="1" placeholder="例如 800" disabled>
      </div>
    </div>
    <button type="button" id="sendspin-sync-apply" class="btn-secondary btn-full" style="margin-top:10px" onclick="saveOutputDelay()">应用同步设置</button>
    <div id="sendspin-sync" class="sync-status-box" role="status">同步：等待状态…</div>
    <div class="form-group" style="margin-top:12px;margin-bottom:0">
      <label for="sendspin-url">Music Assistant WebSocket 地址（默认端口 8927）</label>
      <input id="sendspin-url" type="text" placeholder="自动发现，或 ws://music-assistant:8927/sendspin">
    </div>
    <div class="grid-2" style="margin-top:10px">
      <button type="button" class="btn-secondary" onclick="saveSendspinConfig()">保存并连接</button>
      <button type="button" class="btn-secondary" onclick="sendspinCommand('open_ui')">打开电视音乐页</button>
    </div>
  </div>

  <!-- 传音简录 (历史记录) -->
  <div class="card">
    <div class="card-title">
      <span id="card-history-title">${if (theme.isXianxia) "📜 传音简录 (最近 20 条)" else "📜 通知历史 (最近 20 条)"}</span>
      <button type="button" class="history-btn" onclick="fetchHistory()">🔄 刷新列表</button>
    </div>
    <div id="history-container">正在载入简录...</div>
  </div>

  <!-- cURL 示例 -->
  <div class="card">
    <div class="card-title">
      <span>💻 cURL 极速调用代码</span>
    </div>
    <div class="code-box">
      <div class="code-header">
        <span>HTTP POST JSON 示例</span>
        <button type="button" class="copy-btn" onclick="copyCurl()">复制</button>
      </div>
      <pre id="curl-code"></pre>
    </div>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
const TOKEN = "$token";
const IP_PORT = "$ip:$port";
const NOTIFY_URL = window.location.origin + "/" + TOKEN + "/notify";
const DISMISS_URL = window.location.origin + "/" + TOKEN + "/dismiss";
const CONFIG_URL = window.location.origin + "/" + TOKEN + "/config";
const HISTORY_URL = window.location.origin + "/" + TOKEN + "/history";
const SENDSPIN_URL = window.location.origin + "/" + TOKEN + "/sendspin";

let currentTheme = "${theme.storageId}";
let currentMuted = $isMuted;
let currentPosition = "$defaultPosition";

const THEME_META = {
  xiuxian: { emoji: "🔮", name: "修仙玉简", classical: true },
  default: { emoji: "🍎", name: "tvOS 极简", classical: false },
  palace: { emoji: "🏮", name: "大唐鎏金", classical: true },
  cyberpunk: { emoji: "⚡", name: "赛博霓虹", classical: false },
  wukong: { emoji: "🐵", name: "黑神话·暗金", classical: true },
  matrix: { emoji: "💻", name: "极客终端", classical: false },
  ink_light: { emoji: "🍵", name: "江南宣纸", classical: true },
  gamer: { emoji: "🏆", name: "主机奖杯", classical: false },
  minecraft: { emoji: "🧱", name: "方块世界", classical: false }
};

function isClassicalTheme(key) {
  return (THEME_META[key] && THEME_META[key].classical) || false;
}

function toast(msg) {
  const t = document.getElementById("toast");
  t.textContent = msg;
  t.style.display = "block";
  setTimeout(() => { t.style.display = "none"; }, 2000);
}

function applyTheme(themeKey) {
  currentTheme = themeKey;
  document.body.setAttribute("data-theme", themeKey);
  const meta = THEME_META[themeKey] || THEME_META.xiuxian;
  const isX = meta.classical;

  document.getElementById("brand-icon").textContent = meta.emoji;
  document.getElementById("brand-title").textContent = isX ? "玉简 · 传音控制台" : "玉简 · 通知控制台";
  document.getElementById("brand-sub").textContent = isX ? "灵讯横幅 · 电视大屏局域网传音" : "局域网通知横幅 · 电视大屏悬浮通知";
  document.getElementById("card-send-title").textContent = isX ? "🚀 传音入室" : "📢 发送通知";
  document.getElementById("btn-send").textContent = isX ? "🚀 立即传音" : "🚀 立即发送";
  document.getElementById("btn-dismiss").textContent = isX ? "🧹 消散当前横幅" : "🧹 关闭当前横幅";
  document.getElementById("card-control-title").textContent = isX ? "🎛️ 电视阵法调控" : "🎛️ 电视服务调控";
  document.getElementById("card-history-title").textContent = isX ? "📜 传音简录 (最近 20 条)" : "📜 通知历史 (最近 20 条)";
  document.getElementById("config-theme").value = themeKey;
  const reqThemeDefaultOpt = document.querySelector('#req-theme option[value=""]');
  if (reqThemeDefaultOpt) {
    reqThemeDefaultOpt.textContent = "跟随电视全局主题（当前：" + meta.emoji + " " + meta.name + "）";
  }

  document.getElementById("opt-info").textContent = isX ? "传音 (Info)" : "普通 (Info)";
  document.getElementById("opt-warn").textContent = isX ? "灵警 (Warn)" : "警告 (Warn)";
  document.getElementById("opt-error").textContent = isX ? "告急 (Error)" : "紧急 (Error)";

  updateStatusPill();
  updateMuteButton();
}

function updateStatusPill() {
  const isX = isClassicalTheme(currentTheme);
  document.getElementById("pill-online").textContent = (isX ? "阵法在线 · " : "服务在线 · ") + IP_PORT;
  document.getElementById("pill-muted").textContent = "· " + (currentMuted ? (isX ? "闭关免扰" : "静音模式") : (isX ? "灵音畅通" : "提示声音"));
  document.getElementById("pill-pos").textContent = "· " + (isX ? "方位：" : "位置：") + currentPosition;
}

function updateMuteButton() {
  const isX = isClassicalTheme(currentTheme);
  document.getElementById("btn-toggle-mute").textContent = currentMuted 
    ? (isX ? "🔇 闭关免扰 (已开启静音)" : "🔇 静音模式 (已开启)") 
    : (isX ? "🔊 灵音畅通 (已开启声音)" : "🔊 提示音效 (已开启)");
}

let sendspinStatus = null;
let sendspinSyncDirty = false;
let sendspinSyncEditVersion = 0;
let sendspinAddressDirty = false;
let sendspinAddressEditVersion = 0;
let sendspinStatusRequest = 0;
function updateSyncInput() {
  document.getElementById("sendspin-delay").disabled = document.getElementById("sendspin-sync-mode").value === "auto";
}
["sendspin-sync-mode", "sendspin-delay"].forEach(id => {
  ["input", "change"].forEach(event => document.getElementById(id).addEventListener(event, () => {
    sendspinSyncDirty = true;
    sendspinSyncEditVersion++;
    updateSyncInput();
  }));
});
["input", "change"].forEach(event => document.getElementById("sendspin-url").addEventListener(event, () => {
  sendspinAddressDirty = true;
  sendspinAddressEditVersion++;
}));
function syncStatusText(status) {
  const source = String(status.position_source || "unavailable").toLowerCase();
  const sourceLabel = source.includes("timestamp") ? "可靠时间戳"
    : source.includes("head") ? "可靠播放位置"
    : source.includes("estimat") ? "估算位置" : "位置不可用";
  const states = { idle: "未播放", waiting_clock: "等待时钟", buffering: "缓冲中", playing: "播放中", estimated: "估算同步", rebuffering: "重新缓冲", paused: "已暂停", error: "同步异常" };
  const state = states[status.sync_state] || (status.clock_ready ? "同步中" : "等待时钟");
  const error = status.sync_error_ms;
  const errorLabel = typeof error === "number" && Number.isFinite(error)
    ? (error > 0 ? "电视偏晚 " : error < 0 ? "电视偏早 " : "偏差 ") + Math.abs(error).toFixed(1) + " ms"
    : "偏差暂不可用";
  return "同步：" + state + " · " + sourceLabel + " · " + errorLabel
    + " · 重缓冲 " + (status.resync_count || 0) + " / 欠载 " + (status.underrun_count || 0);
}
async function fetchSendspinStatus() {
  const request = ++sendspinStatusRequest;
  try {
    const res = await fetch(SENDSPIN_URL, { cache: "no-store" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    const status = await res.json();
    if (request !== sendspinStatusRequest) return;
    sendspinStatus = status;
    document.getElementById("sendspin-state").textContent = status.connected
      ? "已连接 · " + (status.server || "Music Assistant")
      : (status.error || status.state || "未连接");
    document.getElementById("sendspin-track").textContent = status.title || "未播放";
    document.getElementById("sendspin-artist").textContent = [status.artist, status.album].filter(Boolean).join(" · ");
    document.getElementById("sendspin-sync").textContent = syncStatusText(status);
    const address = document.getElementById("sendspin-url");
    if (!sendspinAddressDirty && document.activeElement !== address) address.value = status.server_url || "";
    const mode = document.getElementById("sendspin-sync-mode");
    const delay = document.getElementById("sendspin-delay");
    if (!sendspinSyncDirty && document.activeElement !== mode && document.activeElement !== delay) {
      mode.value = status.sync_mode === "manual" || status.automatic_sync === false ? "manual" : "auto";
      delay.value = status.manual_output_delay_ms != null ? status.manual_output_delay_ms : "";
      updateSyncInput();
    }
  } catch (_) {
    if (request !== sendspinStatusRequest) return;
    document.getElementById("sendspin-state").textContent = "状态暂不可用";
    document.getElementById("sendspin-sync").textContent = "同步：状态暂不可用";
  }
}
async function sendspinCommand(command, extra = {}) {
  const actual = command === "play_pause" ? ((sendspinStatus && sendspinStatus.playing) ? "pause" : "play") : command;
  if (actual === "volume_up" || actual === "volume_down") {
    const supported = (sendspinStatus && sendspinStatus.player_supported_commands) || [];
    if (!supported.includes("volume")) { toast("播放器未提供本地音量控制"); return; }
    extra = { volume: Math.max(0, Math.min(100, (sendspinStatus.volume || 100) + (actual === "volume_up" ? 5 : -5))) };
  }
  const supported = (sendspinStatus && sendspinStatus.supported_commands) || [];
  const playerSupported = (sendspinStatus && sendspinStatus.player_supported_commands) || [];
  const playerAction = ["volume_up", "volume_down", "mute"].includes(actual);
  const wireCommand = ["volume_up", "volume_down"].includes(actual) ? "volume" : actual;
  const controlSupported = playerAction ? playerSupported : supported;
  if (actual === "mute") extra = { muted: !(sendspinStatus && sendspinStatus.muted) };
  if (!["open_ui", "close_ui"].includes(actual) && !controlSupported.includes(actual)) {
    toast("Music Assistant 未公布此控制命令"); return;
  }
  try {
    const body = ["open_ui", "close_ui"].includes(actual)
      ? { action: actual }
      : { action: "command", command: wireCommand, ...extra };
    const res = await fetch(SENDSPIN_URL, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    const result = await res.json();
    if (!result.ok) toast("Sendspin 命令未发送：" + (result.error || "未连接"));
    else setTimeout(fetchSendspinStatus, 250);
  } catch (e) { toast("Sendspin 控制失败：" + e.message); }
}
async function saveSendspinConfig() {
  const editVersion = sendspinAddressEditVersion;
  try {
    const res = await fetch(SENDSPIN_URL, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action: "config", enabled: true, server_url: document.getElementById("sendspin-url").value.trim() }) });
    const result = await res.json();
    if (!res.ok || result.ok !== true) throw new Error(result.error || "HTTP " + res.status);
    if (editVersion === sendspinAddressEditVersion) sendspinAddressDirty = false;
    sendspinStatusRequest++;
    toast("Sendspin 设置已保存");
    fetchSendspinStatus();
  } catch (e) { toast("保存失败：" + e.message); }
}
async function saveOutputDelay() {
  const mode = document.getElementById("sendspin-sync-mode").value;
  if (!["auto", "manual"].includes(mode)) { toast("请选择同步模式"); return; }
  const body = { action: "config", sync_mode: mode };
  if (mode === "manual") {
    const raw = document.getElementById("sendspin-delay").value.trim();
    if (!/^[0-9]+$/.test(raw) || !Number.isSafeInteger(Number(raw)) || Number(raw) > 5000) {
      toast("请输入 0–5000 的整数毫秒值"); return;
    }
    body.output_delay_ms = Number(raw);
  }
  const editVersion = sendspinSyncEditVersion;
  const button = document.getElementById("sendspin-sync-apply");
  button.disabled = true;
  try {
    const res = await fetch(SENDSPIN_URL, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    const result = await res.json();
    if (!res.ok || result.ok !== true) throw new Error(result.error || "HTTP " + res.status);
    if (editVersion === sendspinSyncEditVersion) sendspinSyncDirty = false;
    sendspinStatusRequest++;
    toast(mode === "auto" ? "已启用自动同步" : "手动固定补偿已设为 " + body.output_delay_ms + " ms");
    fetchSendspinStatus();
  } catch (e) { toast("保存失败：" + e.message); }
  finally { button.disabled = false; }
}
setInterval(fetchSendspinStatus, 2500);
fetchSendspinStatus();

function toggleDetails() {
  const box = document.getElementById("details-box");
  box.style.display = box.style.display === "block" ? "none" : "block";
}

function buildPayload() {
  const p = {
    title: document.getElementById("title").value.trim(),
    text: document.getElementById("text").value.trim(),
    level: document.getElementById("level").value,
    duration: parseInt(document.getElementById("duration").value) || 5,
    sound: document.getElementById("sound").checked,
    sticky: document.getElementById("sticky").checked
  };
  const pos = document.getElementById("position").value;
  if (pos) p.position = pos;
  const reqTheme = document.getElementById("req-theme").value;
  if (reqTheme) p.theme = reqTheme;
  if (document.getElementById("tts").checked) p.tts = true;
  if (document.getElementById("queue").checked) p.queue = true;

  const img = document.getElementById("image").value.trim();
  if (img) p.image = img;
  const interval = parseInt(document.getElementById("image_interval").value) || 0;
  if (interval > 0) p.image_interval = interval;
  const vid = document.getElementById("video").value.trim();
  if (vid) p.video = vid;
  const idVal = document.getElementById("id").value.trim();
  if (idVal) p.id = idVal;

  return p;
}

function updateCurlDisplay() {
  const p = buildPayload();
  const jsonStr = JSON.stringify(p);
  const cmd = "curl -X POST -H \"Content-Type: application/json\" -d '" + jsonStr + "' " + NOTIFY_URL;
  document.getElementById("curl-code").textContent = cmd;
}

document.querySelectorAll("input, textarea, select").forEach(el => {
  el.addEventListener("input", updateCurlDisplay);
  el.addEventListener("change", updateCurlDisplay);
});
updateCurlDisplay();

async function sendNotify() {
  const payload = buildPayload();
  if (!payload.text && !payload.title && !payload.image && !payload.video) {
    toast("标题、正文或图片至少需填一项");
    return;
  }
  try {
    const res = await fetch(NOTIFY_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    const data = await res.json();
    if (data.ok) {
      toast("✨ 传音成功！横幅已在电视展现");
      setTimeout(fetchHistory, 500);
    } else {
      toast("❌ 传音失败：" + (data.error || "未知原因"));
    }
  } catch (e) {
    toast("❌ 网络异常：" + e.message);
  }
}

async function dismissBanner() {
  try {
    const res = await fetch(DISMISS_URL, { method: "POST" });
    const data = await res.json();
    if (data.ok) {
      toast(data.dismissed ? "🧹 横幅已消散" : "无正在展示之横幅");
    }
  } catch (e) {
    toast("❌ 消散失败: " + e.message);
  }
}

async function toggleMute() {
  currentMuted = !currentMuted;
  try {
    const res = await fetch(CONFIG_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ muted: currentMuted })
    });
    const data = await res.json();
    if (data.ok) {
      currentMuted = data.muted;
      updateStatusPill();
      updateMuteButton();
      toast(currentMuted ? "已切换至闭关免扰（静音）" : "已开启灵音畅通（发声）");
    }
  } catch (e) {
    toast("❌ 配置更新失败: " + e.message);
  }
}

async function changeGlobalTheme() {
  const targetTheme = document.getElementById("config-theme").value;
  try {
    const res = await fetch(CONFIG_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ theme: targetTheme })
    });
    const data = await res.json();
    if (data.ok) {
      const actual = data.theme || targetTheme;
      applyTheme(actual);
      const meta = THEME_META[actual] || THEME_META.xiuxian;
      toast("全局主题已切换为：" + meta.emoji + " " + meta.name);
      fetchHistory();
    }
  } catch (e) {
    toast("❌ 切换主题失败: " + e.message);
  }
}

async function changeDefaultPosition() {
  const pos = document.getElementById("config-pos").value;
  try {
    const res = await fetch(CONFIG_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ position: pos })
    });
    const data = await res.json();
    if (data.ok) {
      currentPosition = data.position;
      updateStatusPill();
      toast("默认方位已更新为：" + data.position);
    }
  } catch (e) {
    toast("❌ 更新失败: " + e.message);
  }
}

async function fetchHistory() {
  const cont = document.getElementById("history-container");
  const isX = isClassicalTheme(currentTheme);
  try {
    const res = await fetch(HISTORY_URL);
    const list = await res.json();
    if (!list || list.length === 0) {
      cont.innerHTML = '<div style="font-size:12px;color:var(--text-muted);text-align:center;padding:12px;">' + (isX ? "暂无传音简录" : "暂无通知历史") + '</div>';
      return;
    }
    window._historyItems = list;
    cont.innerHTML = list.map((item, index) => {
      const lvl = (item.level || "info").toLowerCase();
      const badgeText = lvl === "warn" ? (isX ? "灵警" : "警告") : lvl === "error" ? (isX ? "告急" : "错误") : (isX ? "传音" : "通知");
      return `
        <div class="history-item">
          <div class="history-info">
            <div class="history-meta">
              <span class="history-level level-${'$'}{lvl}">${'$'}{badgeText}</span>
              <span>${'$'}{new Date(item.timestamp).toLocaleTimeString()}</span>
              <span>${'$'}{isX ? '方位: ' : '位置: '}${'$'}{item.position}</span>
              ${'$'}{item.tts ? '<span style="color:var(--accent);">🔊 TTS</span>' : ''}
            </div>
            <div class="history-title">${'$'}{escapeHtml(item.title || (isX ? "（无标题）" : "（无标题）"))}</div>
            <div class="history-text">${'$'}{escapeHtml(item.text || "")}</div>
          </div>
          <button class="history-btn" onclick="resendIndex(${'$'}{index})">${'$'}{isX ? '重演' : '重发'}</button>
        </div>
      `;
    }).join("");
  } catch (e) {
    cont.innerHTML = '<div style="font-size:12px;color:var(--danger);padding:8px;">加载历史失败</div>';
  }
}

function resendIndex(idx) {
  const item = window._historyItems && window._historyItems[idx];
  if (!item) return;
  document.getElementById("title").value = item.title || "";
  document.getElementById("text").value = item.text || "";
  document.getElementById("level").value = item.level || "info";
  if (item.position) document.getElementById("position").value = item.position;
  if (item.tts) document.getElementById("tts").checked = true;
  updateCurlDisplay();
  sendNotify();
}

function escapeHtml(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function copyCurl() {
  const text = document.getElementById("curl-code").textContent;
  navigator.clipboard.writeText(text).then(() => {
    toast("cURL 命令已复制到剪贴板");
  }).catch(() => {
    toast("复制失败，请手动选取");
  });
}

fetchHistory();
</script>
</body>
</html>"""
    }
}
