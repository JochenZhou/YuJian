package com.zqc.tvnotify

internal object ApkUpdateValidator {
    data class Package(val name: String, val versionCode: Long, val signatures: Set<String>)

    fun rejection(installed: Package, candidate: Package?): String? = when {
        candidate == null -> "安装包无法解析，文件可能不完整"
        candidate.name != installed.name -> "安装包不属于玉简，已阻止安装"
        candidate.versionCode <= installed.versionCode -> "安装包不是更新版本，已阻止重复安装或降级"
        installed.signatures.isEmpty() || candidate.signatures != installed.signatures -> "安装包签名与当前应用不一致，已阻止安装"
        else -> null
    }
}
