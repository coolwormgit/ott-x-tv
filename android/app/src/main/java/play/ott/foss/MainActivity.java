package play.ott.foss;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

import play.ott.foss.plugin.MobileXmltvEpgPlugin;

/**
 * Android TV entry point.
 *
 * Two things differ from a phone build:
 *
 * 1. Remote keys are forwarded to the web app as *raw Android key codes*.
 *    The Android device layer (`stb/android/stb.js`, served as
 *    `/stb/android/stb.js` and merged into `window.keys` by the app) already
 *    declares the Android key codes (UP=19, DOWN=20, ENTER=66, BACK/EXIT=4,
 *    PLAY_PAUSE=85, INFO=165, PROG_*=183..186, N0..N9=7..16, ...), so the native
 *    side must not translate them to browser key codes — that mismatch is why
 *    a D-pad press used to be swallowed with no effect.
 *
 * 2. Every app-local Capacitor plugin is registered explicitly. Capacitor only
 *    auto-loads plugins listed in `assets/capacitor.plugins.json` (i.e. npm
 *    plugins); app-local classes are never discovered automatically.
 */
public class MainActivity extends BridgeActivity {

    /** Two BACK presses within this window exit from the TV home screen. */
    private static final long BACK_EXIT_WINDOW_MS = 1500;

    private long lastBackAt = 0L;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Must be registered before super.onCreate(): BridgeActivity builds the
        // bridge (and resolves plugins) there.
        registerPlugin(DashExoPlayerPlugin.class);
        registerPlugin(M3UProxyPlugin.class);
        registerPlugin(MobileCommandQueuePlugin.class);
        registerPlugin(MobileNativeMediaPlugin.class);
        registerPlugin(MobileXmltvEpgPlugin.class);
        registerPlugin(StalkerPortalPlugin.class);

        super.onCreate(savedInstanceState);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return super.dispatchKeyEvent(event);
        }

        int code = event.getKeyCode();
        int jsCode = toJsKeyCode(code);
        if (jsCode < 0) {
            // Not a key the web app handles (HOME, POWER off, system volume,
            // menu of the TV itself, ...) — let Android deal with it.
            return super.dispatchKeyEvent(event);
        }

        if (code == KeyEvent.KEYCODE_BACK) {
            long now = SystemClock.uptimeMillis();
            boolean exit = (now - lastBackAt) < BACK_EXIT_WINDOW_MS;
            lastBackAt = now;
            if (exit) {
                finish();
                return true;
            }
        }

        if (injectKey(jsCode)) {
            return true;
        }
        // WebView not ready yet (early boot): do not swallow the key forever.
        return super.dispatchKeyEvent(event);
    }

    /**
     * Map an Android key code to the code the app's Android device layer
     * expects. Returns -1 for keys the app deliberately ignores or that belong
     * to the TV system (HOME, POWER, TV input, ...).
     *
     * Most codes are 1:1 because the device layer is written in Android key
     * codes; only D-pad centre, the channel rocker and the dedicated play/pause
     * keys need translating.
     */
    private int toJsKeyCode(int code) {
        switch (code) {
            // D-pad + confirm
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return code; // 19 / 20 / 21 / 22 — matches keys.UP..RIGHT
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A: // gamepad A = OK
                return 66; // keys.ENTER
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                return 4; // keys.RETURN / keys.EXIT
            case KeyEvent.KEYCODE_MENU:
                return 82; // keys.SETUP / keys.TOOLS
            case KeyEvent.KEYCODE_INFO:
                return 165; // keys.INFO

            // Channel rocker: the device layer declares CH_UP=167 / CH_DOWN=168,
            // which is one step away from Android's own 166/167 — translate so
            // CH+ really goes up and CH- really goes down.
            case KeyEvent.KEYCODE_CHANNEL_UP:
                return 167;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                return 168;

            // Transport
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return 85; // keys.PLAY / keys.PAUSE
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return 86; // keys.STOP
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return 87; // keys.NEXT
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return 88; // keys.PREV
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return 89; // keys.RW
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return 90; // keys.FF

            // Volume / mute handled in-app (same as the webOS build)
            case KeyEvent.KEYCODE_VOLUME_UP:
                return 24; // keys.VOL_UP
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return 25; // keys.VOL_DOWN
            case KeyEvent.KEYCODE_MUTE:
                return 91; // keys.MUTE

            // Colour buttons
            case KeyEvent.KEYCODE_PROG_RED:
                return 183;
            case KeyEvent.KEYCODE_PROG_GREEN:
                return 184;
            case KeyEvent.KEYCODE_PROG_YELLOW:
                return 185;
            case KeyEvent.KEYCODE_PROG_BLUE:
                return 186;

            default:
                // Digits arrive as KEYCODE_0..KEYCODE_9 (7..16), which the
                // device layer maps to keys.N0..keys.N9 — 1:1.
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                    return code;
                }
                return -1;
        }
    }

    /** Send the key to the web app; false when the bridge/WebView is not ready. */
    private boolean injectKey(int jsCode) {
        if (getBridge() == null) return false;
        WebView webView = getBridge().getWebView();
        if (webView == null) return false;
        webView.evaluateJavascript("window._doKey && window._doKey(" + jsCode + ")", null);
        return true;
    }
}
