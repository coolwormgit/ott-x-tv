/**
 * Android TV device layer — TV build override.
 *
 * The shared copy at OTT-X/stb/android/stb.js ends with
 *
 *     var _baseStbInit = typeof stbInit === "function" ? stbInit : function () {};
 *     function stbInit() { _baseStbInit(); ... }
 *
 * Because function declarations hoist, `stbInit` there is the *new* function,
 * so `_baseStbInit` captures itself and every boot dies with
 * "RangeError: Maximum call stack size exceeded at stbInit". (Verified in a
 * browser boot of the built payload.) The LG layer already carries the fix; see
 * OTT-X/stb/lg/webos/stb.js ("must be an explicit assignment — a plain
 * `function stbInit()` would hoist …"). This file is that same fix, adapted for
 * Android, and the TV build copies it over the staged copy so the shared
 * checkout itself stays untouched.
 *
 * Behaviour kept identical to the shared file: same `keys` table (the native
 * side in MainActivity.java injects exactly these codes) and the same version
 * tag, so device diagnostics keep working.
 */

version += " android-0219";
var keys = {
    ASPECT: 0,
    AUDIO: 0,
    BLUE: 186,
    CH_DOWN: 168,
    CH_LIST: 0,
    CH_UP: 167,
    DOWN: 20,
    ENTER: 66,
    EPG: 0,
    EXIT: 4,
    FF: 90,
    GREEN: 184,
    INFO: 165,
    LANG: 0,
    LEFT: 21,
    MUTE: 91,
    N0: 7,
    N1: 8,
    N2: 9,
    N3: 10,
    N4: 11,
    N5: 12,
    N6: 13,
    N7: 14,
    N8: 15,
    N9: 16,
    NEXT: 87,
    PAUSE: 85,
    PIP: 0,
    PLAY: 85,
    POWER: 26,
    PRECH: 0,
    PREV: 88,
    REC: 0,
    RED: 183,
    RETURN: 4,
    RIGHT: 22,
    RW: 89,
    SETUP: 82,
    STOP: 86,
    TOOLS: 82,
    UP: 19,
    VOL_DOWN: 25,
    VOL_UP: 24,
    YELLOW: 185,
    ZOOM: 0,
};
var strEXIT = "BACK";
var strENTER = "OK";
var strRETURN = "BACK";
var strSETUP = "MENU";

// Captured at load time, before the assignment below replaces window.stbInit.
var _baseStbInit = typeof window.stbInit === "function" ? window.stbInit : function () {};

function androidTvStbInit() {
    try {
        _baseStbInit();
    } catch (e) {
        console.error("[stb] base stbInit failed:", e);
    }
    try {
        if (typeof Android !== "undefined" || typeof window.Capacitor !== "undefined") {
            console.log("[stb] Android TV platform detected");
        }
    } catch (e) {}
}

// Explicit assignment (NOT a `function stbInit()` declaration) — a declaration
// would hoist and make _baseStbInit capture itself.
window.stbInit = androidTvStbInit;
