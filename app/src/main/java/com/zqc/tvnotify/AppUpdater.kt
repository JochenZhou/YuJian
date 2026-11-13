package com.zqc.tvnotify

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.regex.Pattern

/**
 * 玉简（YuJian）应用内全自动升级组件：
 * 1. 双通道检测：GitHub Releases API 优先，国内加速/CDN 兜底，免科学上网秒检；
 * 2. 智能版本比对：语义化版本号解析，避免降级或误报；
 * 3. 多镜像断点/容灾下载：官方 Release 直链 + 国内高速镜像切换，校验 ZIP/APK 魔数；
 * 4. Android 6.0+ 优先由只读 ApkFileProvider 交付安装包，仅支持 file:// 的 API 23 电视回退至内部缓存目录穿透授权，安装前核验包名、版本与签名。
 */
object AppUpdater {
    private const val TAG = "AppUpdater"

    private const val GITHUB_API_URL = "https://api.github.com/repos/JochenZhou/YuJian/releases/latest"
    private const val JSDELIVR_GRADLE_URL = "https://cdn.jsdelivr.net/gh/JochenZhou/YuJian@main/app/build.gradle.kts"
    private const val GHFAST_GRADLE_URL = "https://ghfast.top/https://raw.githubusercontent.com/JochenZhou/YuJian/main/app/build.gradle.kts"

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val releaseNotes: String,
        val downloadUrl: String,
        val fileSize: Long
    )

    @Volatile
    var latestUpdate: UpdateInfo? = null
        private set

    @Volatile
    private var isChecking = false

    @Volatile
    private var isDownloading = false

    private var userDismissedVersion: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun getButtonText(context: Context, theme: Theme): String {
        val update = latestUpdate
        return if (update != null) {
            theme.updateAvailableButtonText(update.versionName)
        } else {
            theme.updateButtonText
        }
    }

    /**
     * 执行版本检测：
     * @param activity 宿主 Activity
     * @param manual true 为遥控器手动点击（有 Toast 提示），false 为启动时后台静默检测
     * @param onComplete 检测完毕回调（供刷新按钮文案）
     */
    fun checkUpdate(activity: Activity, manual: Boolean, onComplete: (() -> Unit)? = null) {
        if (isChecking) {
            if (manual) {
                val theme = Theme.current(activity)
                Toast.makeText(activity, theme.checkingUpdateToast, Toast.LENGTH_SHORT).show()
            }
            return
        }
        isChecking = true
        if (manual) {
            val theme = Theme.current(activity)
            Toast.makeText(activity, theme.checkingUpdateToast, Toast.LENGTH_SHORT).show()
        }

        Thread({
            val info = fetchLatestRelease()
            val localName = getLocalVersionName(activity)
            val localCode = getLocalVersionCode(activity)
            val isNewer = if (info != null) {
                if (info.versionCode > 0 && localCode > 0) {
                    info.versionCode > localCode
                } else {
                    isNewerVersion(info.versionName, localName)
                }
            } else {
                false
            }

            mainHandler.post {
                isChecking = false
                val theme = Theme.current(activity)
                if (info != null && isNewer) {
                    latestUpdate = info
                    onComplete?.invoke()
                    if (manual || userDismissedVersion != info.versionName) {
                        showUpdateDialog(activity, info, theme)
                    }
                } else {
                    latestUpdate = null
                    onComplete?.invoke()
                    if (manual) {
                        if (info != null) {
                            Toast.makeText(activity, theme.alreadyLatestToast(localName), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, theme.updateCheckFailToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }, "yujian-ota-check").start()
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        // 1. 优先尝试 GitHub Releases API
        try {
            val conn = SafeNetworkHelper.openConnection(
                urlStr = GITHUB_API_URL,
                connectTimeoutMs = 3500,
                readTimeoutMs = 4500,
                userAgent = "YuJian-App",
                acceptHeader = "application/vnd.github.v3+json, application/json"
            )
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.use { it.bufferedReader().readText() }
                val root = JSONObject(jsonStr)
                val rawTag = root.optString("tag_name", "").trim()
                val cleanTag = rawTag.removePrefix("v").removePrefix("V")
                if (cleanTag.isNotEmpty()) {
                    val body = root.optString("body", "").trim()
                    var apkUrl = ""
                    var apkSize = 0L
                    val assets = root.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url", "")
                                apkSize = asset.optLong("size", 0L)
                                break
                            }
                        }
                    }
                    if (apkUrl.isEmpty()) {
                        apkUrl = "https://github.com/JochenZhou/YuJian/releases/download/$rawTag/YuJian-release.apk"
                    }
                    Log.i(TAG, "GitHub API 检出新版本: $cleanTag, url=$apkUrl")
                    return UpdateInfo(
                        versionName = cleanTag,
                        versionCode = 0,
                        releaseNotes = body,
                        downloadUrl = apkUrl,
                        fileSize = apkSize
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GitHub Releases API 访问异常: ${e.message}")
        }

        // 2. 备选兜底：通过国内 CDN 镜像检测 build.gradle.kts
        val cdnUrls = listOf(JSDELIVR_GRADLE_URL, GHFAST_GRADLE_URL)
        for (url in cdnUrls) {
            try {
                val conn = SafeNetworkHelper.openConnection(
                    urlStr = url,
                    connectTimeoutMs = 3000,
                    readTimeoutMs = 4000,
                    userAgent = "YuJian-App",
                    acceptHeader = "text/plain, */*"
                )
                if (conn.responseCode == 200) {
                    val content = conn.inputStream.use { it.bufferedReader().readText() }
                    val nameMatcher = Pattern.compile("versionName\\s*=\\s*\"([^\"]+)\"").matcher(content)
                    val codeMatcher = Pattern.compile("versionCode\\s*=\\s*(\\d+)").matcher(content)
                    if (nameMatcher.find()) {
                        val verName = nameMatcher.group(1)?.trim() ?: ""
                        val verCode = if (codeMatcher.find()) codeMatcher.group(1)?.toIntOrNull() ?: 0 else 0
                        if (verName.isNotEmpty()) {
                            val downloadUrl = "https://github.com/JochenZhou/YuJian/releases/download/v$verName/YuJian-release.apk"
                            Log.i(TAG, "国内 CDN 检出版本: $verName (code: $verCode)")
                            return UpdateInfo(
                                versionName = verName,
                                versionCode = verCode,
                                releaseNotes = "建议更新至新版体验更稳健的局域网通知服务与新增功能。",
                                downloadUrl = downloadUrl,
                                fileSize = 0L
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "CDN 版本检测异常 ($url): ${e.message}")
            }
        }
        return null
    }

    private fun isNewerVersion(remoteVer: String, localVer: String): Boolean {
        val rClean = remoteVer.trim().removePrefix("v").removePrefix("V")
        val lClean = localVer.trim().removePrefix("v").removePrefix("V")
        if (rClean == lClean) return false
        val rParts = rClean.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = lClean.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun getLocalVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }

    private fun getLocalVersionCode(context: Context): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (_: Exception) {
        0
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateInfo, theme: Theme) {
        if (activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return
        val currentVer = getLocalVersionName(activity)
        val sizeDesc = if (info.fileSize > 0) " (约 ${info.fileSize / 1024} KB)" else ""
        val notes = if (info.releaseNotes.isNotBlank()) {
            "\n\n【更新说明】\n${info.releaseNotes.take(300)}"
        } else ""

        val message = "当前版本：v$currentVer\n最新版本：v${info.versionName}$sizeDesc$notes"

        AlertDialog.Builder(activity)
            .setTitle(theme.updateDialogTitle("v${info.versionName}"))
            .setMessage(message)
            .setPositiveButton(theme.updateDialogAction) { _, _ ->
                startDownload(activity, info, theme)
            }
            .setNegativeButton(theme.updateDialogCancel) { _, _ ->
                userDismissedVersion = info.versionName
            }
            .show()
    }

    private fun startDownload(activity: Activity, info: UpdateInfo, theme: Theme) {
        if (isDownloading) return
        isDownloading = true

        val density = activity.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(18f), dp(22f), dp(12f))
        }

        val titleView = TextView(activity).apply {
            text = theme.downloadingUpdateTitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10f))
        }
        layout.addView(titleView)

        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(12f)
            )
        }
        layout.addView(progressBar)

        val statusView = TextView(activity).apply {
            text = "正在连接更新节点… (0%)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.argb(0xCC, 0xFF, 0xFF, 0xFF))
            setPadding(0, dp(8f), 0, 0)
        }
        layout.addView(statusView)

        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton(if (theme.isXianxia) "作罢 (取消)" else "取消") { d, _ ->
                cancelled.set(true)
                d.dismiss()
            }
            .create()
        dialog.show()

        Thread({
            // Private cache is readable by the installer only through the granted provider URI.
            val updateDir = File(activity.cacheDir, "updates")
            if ((!updateDir.isDirectory && !updateDir.mkdirs()) || !updateDir.canWrite()) {
                mainHandler.post {
                    try { dialog.dismiss() } catch (_: Exception) {}
                    isDownloading = false
                    Toast.makeText(activity, "无法创建升级缓存，请检查可用空间", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val targetApk = File(updateDir, "YuJian-update.apk")
            val tempFile = File(updateDir, "YuJian-update.tmp")

            val downloadCandidates = listOf(
                "https://ghfast.top/${info.downloadUrl}",
                info.downloadUrl,
                "https://ghfast.top/https://github.com/JochenZhou/YuJian/releases/download/v${info.versionName}/YuJian-release.apk"
            )

            var success = false
            var failureReason = theme.downloadFailToast
            for (url in downloadCandidates) {
                if (cancelled.get()) break
                try {
                    mainHandler.post {
                        if (!cancelled.get()) statusView.text = "正在拉取安装包…"
                    }
                    val conn = SafeNetworkHelper.openConnection(
                        urlStr = url,
                        connectTimeoutMs = 4000,
                        readTimeoutMs = 12000,
                        userAgent = "YuJian-OTA",
                        acceptHeader = "application/vnd.android.package-archive, application/octet-stream, */*"
                    )
                    if (conn.responseCode != 200) {
                        conn.disconnect()
                        continue
                    }
                    val totalLength = conn.contentLength.toLong()
                    var downloaded = 0L
                    var lastUpdate = 0L

                    tempFile.delete()
                    FileOutputStream(tempFile).use { fos ->
                        conn.inputStream.use { ins ->
                            val buffer = ByteArray(16384)
                            while (true) {
                                if (cancelled.get()) break
                                val count = ins.read(buffer)
                                if (count < 0) break
                                fos.write(buffer, 0, count)
                                downloaded += count

                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 120 || downloaded == totalLength) {
                                    lastUpdate = now
                                    val progressPct = if (totalLength > 0) ((downloaded * 100) / totalLength).toInt() else 0
                                    val downloadedMb = String.format("%.2f", downloaded / (1024f * 1024f))
                                    val totalMb = if (totalLength > 0) String.format("%.2f", totalLength / (1024f * 1024f)) else "未知"
                                    mainHandler.post {
                                        if (!cancelled.get()) {
                                            progressBar.progress = progressPct
                                            statusView.text = "$progressPct% ($downloadedMb MB / $totalMb MB)"
                                        }
                                    }
                                }
                            }
                        }
                    }

                    conn.disconnect()
                    if (cancelled.get()) { tempFile.delete(); break }
                    if (!isValidApk(tempFile) || (info.fileSize > 0 && tempFile.length() != info.fileSize)) {
                        failureReason = "安装包校验失败：文件不完整或不是 APK"
                        tempFile.delete()
                        continue
                    }
                    val rejection = validateUpdate(activity, tempFile)
                    if (rejection != null) {
                        failureReason = rejection
                        tempFile.delete()
                        continue
                    }
                    check(!targetApk.exists() || targetApk.delete()) { "无法替换旧的升级缓存" }
                    check(tempFile.renameTo(targetApk)) { "无法保存升级安装包" }
                    success = true
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "从节点 $url 下载失败: ${e.message}")
                    failureReason = "下载或保存失败：${e.message ?: e.javaClass.simpleName}"
                    tempFile.delete()
                }
            }

            mainHandler.post {
                try { dialog.dismiss() } catch (_: Exception) {}
                isDownloading = false
                if (cancelled.get()) return@post

                if (success && targetApk.exists()) {
                    installApk(activity, targetApk)
                } else {
                    Toast.makeText(activity, failureReason, Toast.LENGTH_LONG).show()
                }
            }
        }, "yujian-ota-download").start()
    }

    @Suppress("DEPRECATION")
    internal fun validateUpdate(context: Context, file: File): String? = try {
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        fun identity(info: android.content.pm.PackageInfo) = ApkUpdateValidator.Package(
            info.packageName,
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            info.signatures.orEmpty().map { it.toCharsString() }.toSet(),
        )
        ApkUpdateValidator.rejection(identity(installed), candidate?.let(::identity))
    } catch (error: Exception) {
        Log.w(TAG, "APK validation failed", error)
        "安装包校验失败：${error.message ?: error.javaClass.simpleName}"
    }

    private fun isValidApk(file: File): Boolean {
        if (!file.exists() || file.length() < 300_000) return false
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                if (fis.read(header) != 4) return false
                header[0] == 0x50.toByte() &&
                        header[1] == 0x4B.toByte() &&
                        header[2] == 0x03.toByte() &&
                        header[3] == 0x04.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private var pendingInstall: File? = null
    private const val PENDING_INSTALL = "pending_update_apk"
    private var resumingInstall = false

    private fun pendingPrefs(context: Context) = context.getSharedPreferences("yujian_updater", Context.MODE_PRIVATE)

    /** Restore only an updater-cache identifier, never an arbitrary persisted file path. */
    fun resumePendingInstall(activity: Activity) {
        if (Build.VERSION.SDK_INT < 26 || !activity.packageManager.canRequestPackageInstalls() || resumingInstall) return
        val name = pendingPrefs(activity).getString(PENDING_INSTALL, null) ?: return
        if (name != File(name).name || !name.endsWith(".apk", true)) {
            pendingPrefs(activity).edit().remove(PENDING_INSTALL).apply()
            return
        }
        val file = File(activity.cacheDir, "updates/$name")
        resumingInstall = true
        Thread({
            val rejection = validateUpdate(activity, file)
            mainHandler.post {
                resumingInstall = false
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (rejection != null) {
                    pendingInstall = null
                    pendingPrefs(activity).edit().remove(PENDING_INSTALL).apply()
                    Toast.makeText(activity, rejection, Toast.LENGTH_LONG).show()
                } else installApk(activity, file)
            }
        }, "yujian-ota-resume").start()
    }

    /** Prepare an internal-cache APK with world-traverse dirs (+x) and world-read file (+r) for API 23 file:// installers. */
    internal fun prepareLegacyApkFile(context: Context, apkFile: File): File {
        val app = context.applicationContext
        val directory = File(app.cacheDir, "updates")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建安装缓存目录" }
        val canonicalDir = directory.canonicalFile
        val canonicalSource = apkFile.canonicalFile
        val legacyCopy = File(directory, "YuJian-legacy-update.apk")
        val destination = if (canonicalSource.parentFile == canonicalDir) {
            if (apkFile.name != legacyCopy.name && legacyCopy.exists()) {
                legacyCopy.setWritable(true, true)
                legacyCopy.delete()
            }
            apkFile
        } else {
            legacyCopy.also { target ->
                if (target.exists()) {
                    target.setWritable(true, true)
                    check(target.delete()) { "无法清理旧安装缓存" }
                }
                apkFile.inputStream().use { source ->
                    FileOutputStream(target).use { out -> source.copyTo(out) }
                }
            }
        }
        val dataRoot = app.applicationInfo?.dataDir?.let(::File) ?: app.cacheDir.parentFile
        val canonicalRoot = dataRoot?.canonicalFile
        val canonicalCache = app.cacheDir.canonicalFile
        val rootCandidates = listOfNotNull(canonicalRoot, canonicalCache.parentFile, canonicalCache).toSet()
        val lineage = mutableListOf<File>()
        var cursor: File? = canonicalDir
        while (cursor != null) {
            lineage.add(cursor)
            if (cursor in rootCandidates) break
            cursor = cursor.parentFile
        }
        val dirs = linkedSetOf<File>().apply {
            listOfNotNull(dataRoot, canonicalRoot, app.cacheDir, canonicalCache, directory, canonicalDir)
                .forEach { add(it) }
            if (cursor != null) addAll(lineage)
        }
        // Only grant o+x (traverse by known path), never o+r (directory listing), on internal app dirs.
        dirs.forEach { dir -> dir.setExecutable(true, false) }
        check(destination.setReadable(true, false)) { "无法开放安装包读取权限" }
        destination.canonicalFile.setReadable(true, false)
        check(destination.isFile && destination.length() == apkFile.length() && destination.canRead()) { "安装缓存准备失败" }
        return destination
    }

    /** Narrow API23 fallback for OEM installers that cannot resolve content URIs. */
    private fun installWithLegacyFile(context: Context, apkFile: File) {
        val app = context.applicationContext
        Thread({
            try {
                val destination = prepareLegacyApkFile(app, apkFile)
                // Internal cache avoids external storage EACCES when public storage permission is absent on API23.
                mainHandler.post {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.fromFile(destination), "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        pendingPrefs(context).edit().remove(PENDING_INSTALL).apply()
                    } catch (error: Exception) {
                        Log.e(TAG, "Legacy installer handoff failed", error)
                        Toast.makeText(context, "电视安装器启动失败：${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Legacy installer cache failed", error)
                mainHandler.post { Toast.makeText(context, "准备电视安装包失败：${error.message}", Toast.LENGTH_LONG).show() }
            }
        }, "yujian-ota-legacy").start()
    }

    /** Prefer a temporary content read grant, with an API23-only OEM installer fallback. */
    fun installApk(context: Context, apkFile: File) {
        try {
            check(apkFile.isFile && apkFile.canRead()) { "安装包不存在或无法读取" }
            val uri = ApkFileProvider.getUriForFile(context, apkFile)
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                require(apkFile.canonicalFile.parentFile == File(context.cacheDir, "updates").canonicalFile) { "安装包不在内部升级缓存" }
                pendingInstall = apkFile
                check(pendingPrefs(context).edit().putString(PENDING_INSTALL, apkFile.name).commit()) { "无法保存待安装状态" }
                Toast.makeText(context, "请允许玉简安装未知应用，返回后继续安装", Toast.LENGTH_LONG).show()
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            pendingInstall = null
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = android.content.ClipData.newRawUri("YuJian update", uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // MiTV Android 6 ships an installer with only file/package filters. Prefer the
            // protected content grant everywhere; file URIs must never escape on API 24+.
            val fileProbe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(apkFile), intent.type)
            if (Build.VERSION.SDK_INT == 23 && intent.resolveActivity(context.packageManager) == null &&
                fileProbe.resolveActivity(context.packageManager) != null) {
                installWithLegacyFile(context, apkFile)
                return
            }
            context.startActivity(intent)
            pendingPrefs(context).edit().remove(PENDING_INSTALL).apply()
        } catch (error: android.content.ActivityNotFoundException) {
            Log.e(TAG, "No APK installer available", error)
            Toast.makeText(context, "未找到系统安装器；请检查电视的应用安装功能", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Log.e(TAG, "APK installer handoff failed", error)
            Toast.makeText(context, "安装交接失败：${error.message}。安卓 6 请同时检查系统「允许未知来源」设置", Toast.LENGTH_LONG).show()
        }
    }
}
