package play.ott.foss

import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayList
@CapacitorPlugin(name = "MobileCommandQueue")
class MobileCommandQueuePlugin : Plugin() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var isRunningFlag = false

    private val expireSecs = 60.0
    private val deviceCap = 50
    private val deviceTrim = 25
    private val broadcastCap = 100
    private val broadcastTrim = 50

    private val deviceCommands = ConcurrentHashMap<String, MutableList<CommandEntry>>()
    private val broadcastCommands = ArrayList<CommandEntry>()

    data class CommandEntry(
        val data: Map<String, Any>,
        val timestamp: Double
    )

    @PluginMethod
    fun start(call: PluginCall) {
        if (isRunningFlag) {
            call.resolve()
            return
        }

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(18081, 50, java.net.InetAddress.getByName("127.0.0.1"))
                isRunningFlag = true
                bridge.activity.runOnUiThread {
                    notifyListeners("isRunning", JSObject().apply { put("running", true) })
                    call.resolve()
                }
                Log.d("MobileCommandQueue", "Command queue listening on http://127.0.0.1:18081")

                while (isActive) {
                    try {
                        val client = serverSocket!!.accept()
                        launch { handleClient(client) }
                    } catch (e: IOException) {
                        if (isRunningFlag) {
                            Log.e("MobileCommandQueue", "Accept error: ${e.message}")
                        }
                        break
                    }
                }
            } catch (e: IOException) {
                Log.e("MobileCommandQueue", "Failed to bind 127.0.0.1:18081: ${e.message}")
                withContext(Dispatchers.Main) {
                    call.reject("Failed to bind HTTP server: ${e.message}")
                }
            }
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        scope.launch {
            isRunningFlag = false
            try {
                serverSocket?.close()
            } catch (e: IOException) {
                // ignore
            }
            serverSocket = null
            serverJob?.cancel()
            serverJob = null
            deviceCommands.clear()
            broadcastCommands.clear()

            bridge.activity.runOnUiThread {
                notifyListeners("isRunning", JSObject().apply { put("running", false) })
                call.resolve()
            }
        }
    }

    @PluginMethod
    fun isRunning(call: PluginCall) {
        call.resolve(JSObject().apply { put("running", isRunningFlag) })
    }

    @PluginMethod
    fun post(call: PluginCall) {
        val deviceId = call.getString("deviceId") ?: ""
        val raw = call.data ?: run {
            call.reject("No data provided")
            return
        }

        scope.launch {
            val timestamp = System.currentTimeMillis() / 1000.0
            val commandMap = mutableMapOf<String, Any>()

            // Convert JSONObject to Map
            val keys = raw.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = raw.get(key)
                if (value != null) {
                    commandMap[key] = value
                }
            }
            commandMap["ts"] = timestamp

            val entry = CommandEntry(commandMap, timestamp)
            var queued: Int

            if (deviceId.isEmpty()) {
                broadcastCommands.add(entry)
                if (broadcastCommands.size > broadcastCap) {
                    val drop = broadcastCommands.size - broadcastTrim
                    broadcastCommands.subList(0, drop).clear()
                }
                queued = broadcastCommands.size
            } else {
                val list = deviceCommands.computeIfAbsent(deviceId) { ArrayList() }
                list.add(entry)
                if (list.size > deviceCap) {
                    val drop = list.size - deviceTrim
                    list.subList(0, drop).clear()
                }
                queued = list.size
            }

            withContext(Dispatchers.Main) {
                call.resolve(JSObject().apply { put("queued", queued) })
            }
        }
    }

    @PluginMethod
    fun get(call: PluginCall) {
        val deviceId = call.getString("deviceId") ?: ""

        scope.launch {
            val cutoff = System.currentTimeMillis() / 1000.0 - expireSecs
            val result = ArrayList<JSObject>()

            if (deviceId.isEmpty()) {
                val recent = broadcastCommands.filter { it.timestamp > cutoff }
                recent.forEach { entry ->
                    val obj = JSObject()
                    entry.data.forEach { (k, v) -> obj.put(k, v) }
                    result.add(obj)
                }
                broadcastCommands.clear()
            } else {
                val list = deviceCommands[deviceId] ?: emptyList()
                val recent = list.filter { it.timestamp > cutoff }
                recent.forEach { entry ->
                    val obj = JSObject()
                    entry.data.forEach { (k, v) -> obj.put(k, v) }
                    result.add(obj)
                }
                deviceCommands[deviceId] = ArrayList()
            }

            val jsArray = JSObject()
            jsArray.put("commands", result.toTypedArray())
            withContext(Dispatchers.Main) {
                call.resolve(jsArray)
            }
        }
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

                // Read request line
                val requestLine = reader.readLine() ?: return@withContext
                val parts = requestLine.split(" ")
                if (parts.size < 3) return@withContext

                val method = parts[0]
                val url = parts[1]

                // Read headers
                val headers = mutableMapOf<String, String>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.isEmpty()) break
                    val colonIdx = line!!.indexOf(":")
                    if (colonIdx > 0) {
                        headers[line!!.substring(0, colonIdx).trim()] = line!!.substring(colonIdx + 1).trim()
                    }
                }

                val deviceId = extractDeviceId(url)
                val path = url.split("?").firstOrNull() ?: url

                when {
                    method == "OPTIONS" -> {
                        writeCors(writer, 200)
                    }
                    method == "POST" && (path == "/api/webhook/commands" || path == "/webhook/notify") -> {
                        val body = readBody(reader, headers)
                        handlePost(body, deviceId, writer)
                    }
                    method == "GET" && (path == "/api/webhook/commands" || path == "/webhook/poll") -> {
                        handleGet(deviceId, writer)
                    }
                    else -> {
                        writeJson(writer, 404, mapOf("error" to "Not Found", "path" to path))
                    }
                }
            }
        } catch (e: IOException) {
            Log.e("MobileCommandQueue", "Client error: ${e.message}")
        }
    }

    private fun handlePost(body: String, deviceId: String, writer: BufferedWriter) {
        val timestamp = System.currentTimeMillis() / 1000.0
        val commandMap = mutableMapOf<String, Any>()

        try {
            val raw = org.json.JSONObject(body)
            val keys = raw.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = raw.get(key)
                if (value != null) {
                    commandMap[key] = value
                }
            }
        } catch (e: Exception) {
            writeJson(writer, 400, mapOf("error" to "Invalid JSON"))
            return
        }

        commandMap["ts"] = timestamp
        val entry = CommandEntry(commandMap, timestamp)
        var queued: Int

        if (deviceId.isEmpty()) {
            broadcastCommands.add(entry)
            if (broadcastCommands.size > broadcastCap) {
                val drop = broadcastCommands.size - broadcastTrim
                broadcastCommands.subList(0, drop).clear()
            }
            queued = broadcastCommands.size
        } else {
            val list = deviceCommands.computeIfAbsent(deviceId) { ArrayList() }
            list.add(entry)
            if (list.size > deviceCap) {
                val drop = list.size - deviceTrim
                list.subList(0, drop).clear()
            }
            queued = list.size
        }

        writeJson(writer, 200, mapOf("status" to "ok", "queued" to queued))
    }

    private fun handleGet(deviceId: String, writer: BufferedWriter) {
        val cutoff = System.currentTimeMillis() / 1000.0 - expireSecs
        val result = mutableListOf<Map<String, Any>>()

        if (deviceId.isEmpty()) {
            val recent = broadcastCommands.filter { it.timestamp > cutoff }
            recent.forEach { result.add(it.data) }
            broadcastCommands.clear()
        } else {
            val list = deviceCommands[deviceId] ?: emptyList()
            val recent = list.filter { it.timestamp > cutoff }
            recent.forEach { result.add(it.data) }
            deviceCommands[deviceId] = ArrayList()
        }

        // Convert to JSON array manually to avoid org.json dependency issues
        writeJson(writer, 200, result)
    }

    private fun readBody(reader: BufferedReader, headers: Map<String, String>): String {
        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        if (contentLength == 0) return ""

        val buffer = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val r = reader.read(buffer, read, contentLength - read)
            if (r == -1) break
            read += r
        }
        return String(buffer, 0, read)
    }

    private fun extractDeviceId(url: String): String {
        val queryStart = url.indexOf("?")
        if (queryStart < 0) return ""
        val query = url.substring(queryStart + 1)
        for (pair in query.split("&")) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2 && parts[0] == "device_id") {
                return URLDecoder.decode(parts[1], "UTF-8").trim()
            }
        }
        return ""
    }

    private fun writeJson(writer: BufferedWriter, status: Int, body: Any) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        val json = when (body) {
            is String -> body
            is Collection<*> -> org.json.JSONArray(body.toTypedArray()).toString()
            else -> org.json.JSONObject.wrap(body).toString()
        }

        writer.write("HTTP/1.1 $status $statusText\r\n")
        writer.write("Access-Control-Allow-Origin: *\r\n")
        writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.write("Access-Control-Allow-Headers: *\r\n")
        writer.write("Access-Control-Max-Age: 86400\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: ${json.toByteArray().size}\r\n")
        writer.write("\r\n")
        writer.write(json)
        writer.flush()
    }

    private fun writeCors(writer: BufferedWriter, status: Int) {
        writer.write("HTTP/1.1 $status OK\r\n")
        writer.write("Access-Control-Allow-Origin: *\r\n")
        writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.write("Access-Control-Allow-Headers: *\r\n")
        writer.write("Access-Control-Max-Age: 86400\r\n")
        writer.write("Content-Length: 0\r\n")
        writer.write("\r\n")
        writer.flush()
    }
}
