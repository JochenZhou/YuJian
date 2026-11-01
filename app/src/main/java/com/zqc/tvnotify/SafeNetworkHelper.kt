package com.zqc.tvnotify

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 局域网与外网跨版本安全网络通信辅助：
 * 支持自签证书信任与过期根证书穿透（解决 Android 6.0 等旧版系统 HTTPS 握手失败问题），
 * 并自动处理 HTTP 301/302/307/308 重定向。
 */
object SafeNetworkHelper {
    private const val TAG = "YuJianNet"

    @Volatile
    private var warnedInsecureTls = false

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
    })

    val permissiveSslContext: SSLContext by lazy {
        try {
            SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }
        } catch (e: Exception) {
            SSLContext.getDefault()
        }
    }

    val permissiveHostnameVerifier = HostnameVerifier { _, _ -> true }

    fun openConnection(
        urlStr: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        userAgent: String,
        acceptHeader: String
    ): HttpURLConnection {
        var currentUrl = urlStr
        for (hop in 0..3) {
            val url = URL(currentUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", acceptHeader)
            }
            if (conn is HttpsURLConnection) {
                if (!warnedInsecureTls) {
                    warnedInsecureTls = true
                    Log.i(TAG, "HTTPS 连接使用兼容证书模式")
                }
                conn.sslSocketFactory = permissiveSslContext.socketFactory
                conn.hostnameVerifier = permissiveHostnameVerifier
            }
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                throw e
            }
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == 307 || code == 308
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (!location.isNullOrBlank()) {
                    currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        URL(url, location).toString()
                    }
                    continue
                }
            }
            return conn
        }
        val url = URL(currentUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", acceptHeader)
        }
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = permissiveSslContext.socketFactory
            conn.hostnameVerifier = permissiveHostnameVerifier
        }
        return conn
    }
}
