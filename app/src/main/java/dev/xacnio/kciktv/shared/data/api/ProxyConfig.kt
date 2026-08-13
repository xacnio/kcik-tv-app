/**
 * File: ProxyConfig.kt
 *
 * Description: Live, process-wide proxy configuration consumed by RetrofitClient's
 * proxied OkHttpClient. Only traffic to kick.com, web.kick.com and search.kick.com
 * is ever routed through this proxy.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.api

import dev.xacnio.kciktv.shared.data.prefs.AppPreferences

object ProxyConfig {
    const val TYPE_HTTP = "http"
    const val TYPE_SOCKS5 = "socks5"

    const val SCOPE_CRITICAL = "critical"
    const val SCOPE_ALL = "all"

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var type: String = TYPE_SOCKS5

    @Volatile
    var scope: String = SCOPE_CRITICAL

    @Volatile
    var host: String = ""

    @Volatile
    var port: Int = 0

    @Volatile
    var username: String? = null

    @Volatile
    var password: String? = null

    val isConfigured: Boolean
        get() = enabled && host.isNotBlank() && port in 1..65535

    /** Refreshes this config from persisted settings. Call on app start and after edits. */
    fun syncFrom(prefs: AppPreferences) {
        enabled = prefs.proxyEnabled
        type = prefs.proxyType
        scope = prefs.proxyScope
        host = prefs.proxyHost
        port = prefs.proxyPort
        username = prefs.proxyUsername
        password = prefs.proxyPassword
    }
}
