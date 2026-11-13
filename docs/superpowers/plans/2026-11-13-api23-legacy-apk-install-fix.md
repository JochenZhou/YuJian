# Android 6 (API 23) 电视端旧版安装器 `EACCES` 修复实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复小米电视 Android 6.0 (API 23) 在线升级时复制到 `/storage/emulated/0/Android/data/com.zqc.tvnotify/cache/updates/YuJian-legacy-update.apk` 报 `open failed: EACCES (Permission denied)` 的问题，使 API 23 仅支持 `file://` 的系统安装器直接从应用内部升级缓存安全读取已校验的 APK。

**Architecture:** 下载与包名/版本/签名校验继续在内部私有缓存 `File(context.cacheDir, "updates/YuJian-update.apk")` 中完成。当 API 23 设备上的系统安装器无法解析 `content://` 且仅支持 `file://` 时，不再向外部存储 `externalCacheDir`（`/storage/emulated/0/...`，在小米电视 API 23 无 `WRITE_EXTERNAL_STORAGE` 权限或 FUSE 默认挂载命名空间下会触发 `EACCES`）写入副本，而是直接在内部 `File(context.cacheDir, "updates")` 目录中就地准备安装包：对祖先目录链（`dataDir`、`cacheDir`、`cacheDir/updates` 及其规范路径）仅开启目录穿透执行位 `setExecutable(true, false)`（`o+x`，允许按已知完整路径寻址但不允许列目录），对已校验的 APK 文件开启只读位 `setReadable(true, false)`（`0644`），随后通过 `Uri.fromFile` 拉起系统安装器。

**Tech Stack:** Kotlin, Android SDK (minSdk 23, targetSdk 34), JUnit 4, Robolectric 4.14.1

**Spec:** `docs/foreground-audio-and-update.md`

## Global Constraints

- `minSdk = 23`（红米/小米电视 Android 6.0 是硬底线），`targetSdk = 34`，严禁调用高于 API 23 的无版本守卫 API（保持 `NewApi` Lint 零错误）。
- 不在 `AndroidManifest.xml` 中申请 `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` 公共存储权限。
- API 24+ 永远只使用 `ApkFileProvider` 的 `content://` 临时授权，绝不暴露 `file://` URI。
- 内部缓存目录（`dataDir`、`cacheDir`、`cacheDir/updates`）仅设置穿透执行权限 `setExecutable(true, false)`，不设置全局可读列表权限 `setReadable(true, false)`，防止枚举应用私有缓存中的其他文件。
- 安装前必须先通过 `ApkUpdateValidator`（包名、更高 `versionCode`、一致签名）核验。

## Review Focus

- 小米电视 API 23 上 `externalCacheDir` 不可写或返回 `null` 时，`installApk` 仍能成功向仅支持 `file://` 的系统安装器交付可读的内部缓存 APK，不再触发 `/storage/emulated/0/...` 的 `EACCES`。
- 传入已经在 `File(context.cacheDir, "updates")` 下的 `YuJian-update.apk` 时，直接就地设置权限并交付，不发生多余的二次文件拷贝或自覆盖清空。
- 传入不在 `File(context.cacheDir, "updates")` 下的合法 APK 文件时，先复制到内部 `File(context.cacheDir, "updates/YuJian-legacy-update.apk")`（删除可能残留的旧同名文件后写入）再设置权限并交付。
- 祖先目录链（`dataDir`、`cacheDir`、`cacheDir/updates`）设置 `setExecutable(true, false)` 后，APK 文件本身设置 `setReadable(true, false)`，确保跨进程 `com.android.packageinstaller` 按路径读取时不因父目录 `0700` 被内核拒绝。
- API 23 支持 `content://` 的安装器以及所有 API 24+ 设备继续走 `ApkFileProvider` 的 `content://` + `FLAG_GRANT_READ_URI_PERMISSION`，不回退 `file://`。

---

### Task 1: 将 API 23 `file://` 安装器回退改为内部升级缓存就地穿透授权并覆盖测试

**Files:**
- Modify: `app/src/main/java/com/zqc/tvnotify/AppUpdater.kt:490-555`
- Modify: `app/src/test/java/com/zqc/tvnotify/LegacyApkInstallerTest.kt:44-85`
- Modify: `docs/foreground-audio-and-update.md:16-25`

**Interfaces:**
- Consumes: `AppUpdater.installApk(context: Context, apkFile: File)`
- Produces: `internal fun prepareLegacyApkFile(context: Context, apkFile: File): File` in `AppUpdater`

- [ ] **Step 1: 更新 `LegacyApkInstallerTest.kt` 中的失败/回归测试**

在 `app/src/test/java/com/zqc/tvnotify/LegacyApkInstallerTest.kt` 中：
1. 将原测试更新为验证仅支持 `file://` 的 API 23 安装器收到位于内部 `File(app.cacheDir, "updates")` 下的 `file://` URI（不依赖 `externalCacheDir`），且目标 APK 文件可读、内容完整。
2. 新增测试 `` `file-only API23 installer succeeds without touching unwritable external cache` ``：将 `File(app.externalCacheDir, "updates")` 设为只读不可写（或预置不可写目录），调用 `AppUpdater.installApk(app, source)`，断言依然成功发出 `file://` 安装 `Intent`，且 URI 指向内部 `File(app.cacheDir, "updates")`。

```kotlin
    @Test fun `file-only API23 installer gets a readable APK in internal update cache`() {
        val app = RuntimeEnvironment.getApplication()
        val source = File(app.cacheDir, "updates/legacy.apk").apply { parentFile!!.mkdirs(); writeText("verified apk bytes") }
        registerInstaller(app, "file")
        val content = Intent(Intent.ACTION_VIEW).setDataAndType(ApkFileProvider.getUriForFile(app, source), "application/vnd.android.package-archive")
        assertNull("Reproduce OEM missing content filter", content.resolveActivity(app.packageManager))
        AppUpdater.installApk(app, source)
        val deadline = System.nanoTime() + 5_000_000_000L
        var intent: Intent? = null
        while (System.nanoTime() < deadline && intent == null) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            intent = shadowOf(app).nextStartedActivity
            if (intent == null) Thread.sleep(10)
        }
        assertNotNull(intent)
        assertEquals("file", intent!!.data!!.scheme)
        val delivered = File(intent.data!!.path!!)
        assertEquals(File(app.cacheDir, "updates").canonicalFile, delivered.canonicalFile.parentFile)
        assertTrue(delivered.canRead())
        assertEquals("verified apk bytes", delivered.readText())
    }

    @Test fun `file-only API23 installer copies external source into internal update cache`() {
        val app = RuntimeEnvironment.getApplication()
        val outside = File(app.cacheDir, "staging/outside.apk").apply { parentFile!!.mkdirs(); writeText("staged apk bytes") }
        File(app.cacheDir, "updates").mkdirs()
        registerInstaller(app, "file")
        val prepared = AppUpdater.prepareLegacyApkFile(app, outside)
        assertEquals(File(app.cacheDir, "updates/YuJian-legacy-update.apk").canonicalFile, prepared.canonicalFile)
        assertEquals("staged apk bytes", prepared.readText())
    }
```

- [ ] **Step 2: 修改 `AppUpdater.kt` 中的 `installWithLegacyFile` 与 `prepareLegacyApkFile`**

在 `app/src/main/java/com/zqc/tvnotify/AppUpdater.kt` 中新增 `internal fun prepareLegacyApkFile(context: Context, apkFile: File): File` 并重构 `installWithLegacyFile(context: Context, apkFile: File)`，不再访问 `app.externalCacheDir`：

```kotlin
    internal fun prepareLegacyApkFile(context: Context, apkFile: File): File {
        val app = context.applicationContext
        val directory = File(app.cacheDir, "updates")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建安装缓存目录" }
        val destination = if (apkFile.canonicalFile.parentFile == directory.canonicalFile) {
            apkFile
        } else {
            File(directory, "YuJian-legacy-update.apk").also { target ->
                if (target.exists()) target.delete()
                apkFile.inputStream().use { source ->
                    FileOutputStream(target).use { out -> source.copyTo(out) }
                }
            }
        }
        val dataDir = app.applicationInfo?.dataDir?.let(::File) ?: app.cacheDir.parentFile
        listOfNotNull(
            dataDir,
            dataDir?.canonicalFile,
            app.cacheDir,
            app.cacheDir.canonicalFile,
            directory,
            directory.canonicalFile,
        ).distinct().forEach { dir ->
            dir.setExecutable(true, false)
        }
        check(destination.setReadable(true, false)) { "无法开放安装包读取权限" }
        destination.canonicalFile.setReadable(true, false)
        check(destination.isFile && destination.length() == apkFile.length() && destination.canRead()) { "安装缓存准备失败" }
        return destination
    }
```

在 `installWithLegacyFile` 中调用 `prepareLegacyApkFile(app, apkFile)` 获取 `destination`，并在主线程通过 `Uri.fromFile(destination)` 启动 `Intent.ACTION_VIEW`。

- [ ] **Step 3: 同步更新 `docs/foreground-audio-and-update.md` 说明**

更新 `docs/foreground-audio-and-update.md` 第 18 行，说明 API 23 在无 `content://` 安装器而回退 `file://` 时，直接对内部 `cacheDir/updates` 祖先目录开启穿透执行位（`+x`，不可列目录）并对已校验 APK 开启只读位（`0644`），避免小米电视 Android 6 上 `externalCacheDir` 无存储权限导致的 `EACCES`。

- [ ] **Step 4: 运行验证检查并提交**

Run:
```powershell
git diff --check
node --test app/src/test/js/sendspin-console.test.cjs
```
Expected: `git diff --check` 无输出，`node --test` 10/10 PASS（若需发布新版，同步递增 `app/build.gradle.kts` 中的 `versionCode` / `versionName` 并推送 GitHub Actions 运行 Gradle 单测与构建）。
