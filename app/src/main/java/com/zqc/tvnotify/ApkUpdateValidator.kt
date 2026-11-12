package com.zqc.tvnotify

internal object ApkUpdateValidator {
    data class Package(val name: String, val versionCode: Long, val signatures: Set<String>)
    fun rejection(installed: Package, candidate: Package?): String? = null
}
