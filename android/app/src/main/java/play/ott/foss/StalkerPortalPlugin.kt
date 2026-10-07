// Mode B native HTTP for Stalker /stalker_portal/api/, host_ott/swop/a.php,
// and Mag path-shaped /load.php|/c/portal URLs (allowlist + header forward only).
package play.ott.foss

import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.net.HttpURLConnection
import java.net.URL

@CapacitorPlugin(name = "StalkerPortal")
class StalkerPortalPlugin : Plugin() {

    private fun isAllowedUrl(url: String): Boolean {
        return url.contains("/stalker_portal/api/") ||
            url.contains("/stalker_portal/stream/") ||
            url.contains("/swop/a.php") ||
            url.contains("/load.php") ||
            url.contains("/c/portal")
    }

    private fun isForbiddenHeader(name: String): Boolean {
        val n = name.lowercase()
        return n == "host" || n == "content-length" || n == "connection" ||
            n == "transfer-encoding" || n == "upgrade"
    }

    @PluginMethod
    fun portalRequest(call: PluginCall) {
        val rawUrl = call.getString("url") ?: run {
            call.reject("missing url")
            return
        }
        if (!isAllowedUrl(rawUrl)) {
            call.reject("url is not an allowed stalker/swop/load.php/c/portal path")
            return
        }
        val urlStr = rawUrl
        val method = (call.getString("method") ?: "GET").uppercase()
        val bodyString = call.getString("body") ?: ""
        val contentType = call.getString("contentType") ?: "application/json"
        val headersObj = call.getObject("headers")

        Thread {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true

                var contentTypeFromHeaders = false
                if (headersObj != null) {
                    val keys = headersObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (isForbiddenHeader(key)) continue
                        val value = headersObj.optString(key, null) ?: continue
                        if (key.equals("content-type", ignoreCase = true)) {
                            contentTypeFromHeaders = true
                        }
                        conn.setRequestProperty(key, value)
                    }
                }
                if (!contentTypeFromHeaders && bodyString.isNotEmpty()) {
                    conn.setRequestProperty("Content-Type", contentType)
                }
                if (bodyString.isNotEmpty()) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(bodyString.toByteArray(Charsets.UTF_8)) }
                }

                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val ct = conn.contentType ?: "application/octet-stream"

                val setCookieArr = JSArray()
                conn.headerFields?.get("Set-Cookie")?.forEach { setCookieArr.put(it) }

                val ret = JSObject()
                ret.put("status", status)
                ret.put("body", body)
                ret.put("contentType", ct)
                ret.put("setCookie", setCookieArr)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("portalRequest failed: ${e.message}")
            }
        }.start()
    }
}
