package play.ott.foss

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context.AUDIO_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "MobileNativeMedia")
class MobileNativeMediaPlugin : Plugin() {

    companion object {
        private const val TAG = "MobileNativeMedia"
        private const val REQ_POST_NOTIFICATIONS = 44051
    }

    private var isFullscreen = false
    private var backgroundAudioActive = false

    override fun load() {
        super.load()
        bindMediaWebView()
    }

    override fun handleOnResume() {
        super.handleOnResume()
        bindMediaWebView()
    }

    override fun handleOnDestroy() {
        MediaPlaybackService.clearWebView(bridge.webView)
        super.handleOnDestroy()
    }

    private fun bindMediaWebView() {
        try {
            MediaPlaybackService.bindWebView(bridge.webView)
        } catch (e: Exception) {
            Log.w(TAG, "bindWebView failed", e)
        }
    }

    /**
     * Android 13+ requires runtime POST_NOTIFICATIONS for the FGS media notification.
     * Best-effort: request if missing, then still start the service (notification may
     * be suppressed until the user grants).
     */
    private fun ensurePostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val act = activity ?: return
        if (ContextCompat.checkSelfPermission(act, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            ActivityCompat.requestPermissions(
                act,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_POST_NOTIFICATIONS
            )
        } catch (e: Exception) {
            Log.w(TAG, "POST_NOTIFICATIONS request failed", e)
        }
    }

    @PluginMethod
    fun getVolume(call: PluginCall) {
        val am = bridge.context.getSystemService(AUDIO_SERVICE) as? AudioManager
            ?: run {
                call.resolve(JSObject().apply {
                    put("ok", false)
                    put("volume", 0)
                    put("unsupported", true)
                })
                return
            }

        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val pct = if (max > 0) (cur * 100 / max) else 0

        call.resolve(JSObject().apply {
            put("ok", true)
            put("volume", pct)
        })
    }

    @PluginMethod
    fun setVolume(call: PluginCall) {
        val volume = call.getInt("volume", 0)
        val clamped = volume.coerceIn(0, 100)

        val am = bridge.context.getSystemService(AUDIO_SERVICE) as? AudioManager
            ?: run {
                call.resolve(JSObject().apply {
                    put("ok", false)
                    put("volume", clamped)
                    put("unsupported", true)
                })
                return
            }

        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (clamped * max / 100).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)

        call.resolve(JSObject().apply {
            put("ok", true)
            put("volume", clamped)
        })
    }

    @PluginMethod
    fun playPip(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            call.resolve(JSObject().apply { put("ok", false) })
            return
        }

        val activity = bridge.activity
        if (bridge.webView == null) {
            call.resolve(JSObject().apply { put("ok", false) })
            return
        }

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(16, 9))
            .build()

        try {
            activity.enterPictureInPictureMode(params)
            call.resolve(JSObject().apply { put("ok", true) })
        } catch (e: Exception) {
            Log.w(TAG, "playPip failed", e)
            call.resolve(JSObject().apply { put("ok", false) })
        }
    }

    @PluginMethod
    fun stopPip(call: PluginCall) {
        val activity = bridge.activity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode) {
            activity.moveTaskToBack(false)
            call.resolve(JSObject().apply { put("ok", true) })
        } else {
            call.resolve(JSObject().apply { put("ok", true) })
        }
    }

    @PluginMethod
    fun setFullscreen(call: PluginCall) {
        val fullscreen = call.getBool("fullscreen", false)
        isFullscreen = fullscreen
        val activity = bridge.activity

        activity.runOnUiThread {
            if (fullscreen) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                activity.actionBar?.hide()
                bridge.webView?.let { webViewWrap(it, activity.window.decorView) }
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                activity.actionBar?.show()
                restoreSystemUi(activity.window.decorView)
            }
        }

        call.resolve(JSObject().apply { put("ok", true) })
    }

    @PluginMethod
    fun allowSleep(call: PluginCall) {
        bridge.activity.runOnUiThread {
            bridge.webView?.clearFocus()
            bridge.webView?.keepScreenOn = false
        }
        call.resolve(JSObject().apply { put("ok", true) })
    }

    @PluginMethod
    fun preventSleep(call: PluginCall) {
        bridge.activity.runOnUiThread {
            bridge.webView?.keepScreenOn = true
        }
        call.resolve(JSObject().apply { put("ok", true) })
    }

    /**
     * Start the mediaPlayback foreground service so HLS/<video> can keep
     * playing when the activity is backgrounded. Real ContextCompat.startForegroundService —
     * never reports ok without attempting to start the service.
     */
    @PluginMethod
    fun startBackgroundAudio(call: PluginCall) {
        val title = call.getString("title") ?: "OTT-play FOSS"
        val artist = call.getString("artist") ?: "Now playing"
        val ctx = bridge.context
        bindMediaWebView()
        ensurePostNotificationsPermission()
        val intent = Intent(ctx, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_START
            putExtra(MediaPlaybackService.EXTRA_TITLE, title)
            putExtra(MediaPlaybackService.EXTRA_ARTIST, artist)
            val artworkUrl = call.getString("artworkUrl")
            if (artworkUrl != null) putExtra(MediaPlaybackService.EXTRA_ARTWORK_URL, artworkUrl)
            val seekable = call.getBoolean("seekable", false) ?: false
            putExtra(MediaPlaybackService.EXTRA_SEEKABLE, seekable)
            val durationSec = call.getDouble("durationSec")
            if (durationSec != null && seekable) {
                putExtra(MediaPlaybackService.EXTRA_DURATION_MS, (durationSec * 1000.0).toLong())
            }
            val positionSec = call.getDouble("positionSec")
            if (positionSec != null && seekable) {
                putExtra(MediaPlaybackService.EXTRA_POSITION_MS, (positionSec * 1000.0).toLong())
            }
        }
        try {
            ContextCompat.startForegroundService(ctx, intent)
            backgroundAudioActive = true
            call.resolve(JSObject().apply { put("ok", true) })
        } catch (e: Exception) {
            Log.e(TAG, "startBackgroundAudio failed", e)
            backgroundAudioActive = false
            call.resolve(JSObject().apply {
                put("ok", false)
                put("error", e.message ?: "startForegroundService failed")
            })
        }
    }

    /** Pause MediaSession state; keep FGS alive while paused briefly. */
    @PluginMethod
    fun pauseBackgroundAudio(call: PluginCall) {
        if (!backgroundAudioActive) {
            call.resolve(JSObject().apply { put("ok", true) })
            return
        }
        val ctx = bridge.context
        val intent = Intent(ctx, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_PAUSE
            // JS already paused <video>; only sync MediaSession / notification.
            putExtra(MediaPlaybackService.EXTRA_SESSION_ONLY, true)
        }
        try {
            ctx.startService(intent)
            call.resolve(JSObject().apply { put("ok", true) })
        } catch (e: Exception) {
            Log.e(TAG, "pauseBackgroundAudio failed", e)
            call.resolve(JSObject().apply {
                put("ok", false)
                put("error", e.message ?: "pause failed")
            })
        }
    }

    /** Resume MediaSession / notification after pause. */
    @PluginMethod
    fun resumeBackgroundAudio(call: PluginCall) {
        val title = call.getString("title") ?: "OTT-play FOSS"
        val artist = call.getString("artist") ?: "Now playing"
        val ctx = bridge.context
        bindMediaWebView()
        ensurePostNotificationsPermission()
        val intent = Intent(ctx, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_RESUME
            putExtra(MediaPlaybackService.EXTRA_TITLE, title)
            putExtra(MediaPlaybackService.EXTRA_ARTIST, artist)
            val artworkUrl = call.getString("artworkUrl")
            if (artworkUrl != null) putExtra(MediaPlaybackService.EXTRA_ARTWORK_URL, artworkUrl)
            val seekable = call.getBoolean("seekable", false) ?: false
            putExtra(MediaPlaybackService.EXTRA_SEEKABLE, seekable)
            val durationSec = call.getDouble("durationSec")
            if (durationSec != null && seekable) {
                putExtra(MediaPlaybackService.EXTRA_DURATION_MS, (durationSec * 1000.0).toLong())
            }
            val positionSec = call.getDouble("positionSec")
            if (positionSec != null && seekable) {
                putExtra(MediaPlaybackService.EXTRA_POSITION_MS, (positionSec * 1000.0).toLong())
            }
        }
        try {
            if (backgroundAudioActive) {
                ctx.startService(intent)
            } else {
                ContextCompat.startForegroundService(ctx, intent)
                backgroundAudioActive = true
            }
            call.resolve(JSObject().apply { put("ok", true) })
        } catch (e: Exception) {
            Log.e(TAG, "resumeBackgroundAudio failed", e)
            call.resolve(JSObject().apply {
                put("ok", false)
                put("error", e.message ?: "resume failed")
            })
        }
    }

    /** Finish Activity / leave Cap app (fallback if App.exitApp unavailable). */
    @PluginMethod
    fun exitApp(call: PluginCall) {
        val act = activity
        if (act == null) {
            call.reject("no activity")
            return
        }
        act.runOnUiThread {
            try {
                act.finishAndRemoveTask()
            } catch (_e: Exception) {
                act.finish()
            }
        }
        val ret = JSObject()
        ret.put("ok", true)
        call.resolve(ret)
    }

    /** Refresh MediaSession metadata / timeline without restarting the FGS. */
    @PluginMethod
    fun updateBackgroundAudio(call: PluginCall) {
        val title = call.getString("title") ?: "OTT-play FOSS"
        val artist = call.getString("artist") ?: "Now playing"
        val ctx = bridge.context
        bindMediaWebView()
        if (!backgroundAudioActive) {
            // Not started yet — treat as start so first metadata still lands.
            startBackgroundAudio(call)
            return
        }
        val intent = Intent(ctx, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_UPDATE
            putExtra(MediaPlaybackService.EXTRA_TITLE, title)
            putExtra(MediaPlaybackService.EXTRA_ARTIST, artist)
            val artworkUrl = call.getString("artworkUrl")
            if (artworkUrl != null) putExtra(MediaPlaybackService.EXTRA_ARTWORK_URL, artworkUrl)
            val seekable = call.getBoolean("seekable", false) ?: false
            putExtra(MediaPlaybackService.EXTRA_SEEKABLE, seekable)
            val durationSec = call.getDouble("durationSec")
            if (durationSec != null && seekable) {
                putExtra(MediaPlaybackService.EXTRA_DURATION_MS, (durationSec * 1000.0).toLong())
            }
            val positionSec = call.getDouble("positionSec")
            if (positionSec != null && seekable) {
                putExtra(MediaPlaybackService.EXTRA_POSITION_MS, (positionSec * 1000.0).toLong())
            }
        }
        try {
            ctx.startService(intent)
            call.resolve(JSObject().apply { put("ok", true) })
        } catch (e: Exception) {
            Log.e(TAG, "updateBackgroundAudio failed", e)
            call.resolve(JSObject().apply {
                put("ok", false)
                put("error", e.message ?: "update failed")
            })
        }
    }

    /** Stop and tear down the mediaPlayback foreground service. */
    @PluginMethod
    fun stopBackgroundAudio(call: PluginCall) {
        val ctx = bridge.context
        val intent = Intent(ctx, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_STOP
        }
        try {
            ctx.startService(intent)
            ctx.stopService(Intent(ctx, MediaPlaybackService::class.java))
            backgroundAudioActive = false
            call.resolve(JSObject().apply { put("ok", true) })
        } catch (e: Exception) {
            Log.e(TAG, "stopBackgroundAudio failed", e)
            backgroundAudioActive = false
            call.resolve(JSObject().apply {
                put("ok", false)
                put("error", e.message ?: "stopService failed")
            })
        }
    }

    private fun webViewWrap(webView: WebView?, systemUi: View) {
        if (webView == null) return
        systemUi.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun restoreSystemUi(systemUi: View) {
        systemUi.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }
}
