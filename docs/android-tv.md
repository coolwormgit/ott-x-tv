# OTT-X on Android TV — design notes

Everything below was established by reading the shared frontend in `../OTT-X`,
building the TV payload, booting it in a browser as an Android device, and
running `npm run verify`. Nothing in this project modifies the LG/webOS
checkout.

## 1. Payload layout (why a staging step is required)

`index.html` (the shared shell, byte-identical to the webOS one apart from the
version string) boots like this:

```
/detected-device            from pathname (/f/<device>/) or user agent
/dist/stbPlayer.js          window.startPlayer etc.  (~556 KB, same bundle as the LG build)
/stb/<device>/stb.js        device key table + window.stbInit
/stbPlayer/1280.css, /stbPlayer/icon.png
/js/{hls.min.js,jquery-1.11.1.min.js,shaka-player.compiled.js}   (CDN fallback)
/fonts, /prov
```

A Capacitor `webDir` is copied to the *root* of the WebView's asset server, so a
payload assembled as `webDir: "dist"` (the value used by the phone project)
would serve `/stbPlayer.js` at the root and `404` on `/dist/stbPlayer.js`,
`/stb/android/stb.js`, `/js/*`, `/prov/*` and `/fonts/*` — the shell would stop
at "Failed to load player". This project therefore builds its own `www/` with
the Mode A directory shape and points `webDir` at it:

```
www/index.html  www/dist/stbPlayer.js  www/stb/<vendor>/stb.js
www/stbPlayer/  www/js/  www/fonts/  www/prov/  www/favicon.ico
```

`npx cap sync android` then produces `android/app/src/main/assets/public/` with
the same shape (verified).

## 2. TV-shaped Android project

The copied Capacitor scaffold was phone/tablet oriented. Changes:

| Area | Phone project | This project |
|---|---|---|
| `applicationId` | `play.ott.foss` | `play.ott.foss.tv` (own Play listing, side-by-side install) |
| Java package | `play.ott.foss` | unchanged (plugin sources are shared verbatim) |
| Launcher | `LAUNCHER` only | `LEANBACK_LAUNCHER` + `LAUNCHER` |
| `uses-feature` | none | `android.software.leanback required=true`, `android.hardware.touchscreen required=false` |
| Banner | none | `android:banner="@drawable/tv_banner"` (320x180 xhdpi PNG, generated) |
| Orientation | free | `landscape` |
| `colors.xml` | **missing** (see §5) | present (`colorPrimary`, `colorPrimaryDark`, `colorAccent`) |
| Plugins | not registered (see §5) | all six registered in `MainActivity.onCreate` |
| Remote keys | browser key codes (dead, see §3) | raw Android key codes |

`android.software.leanback required="true"` keeps the app out of phone-only Play
listings. Set it to `false` (leaving `touchscreen` at `false`) if the same APK
should also be published for phones/tablets.

## 3. Remote control mapping (the important native change)

`window._doKey(code)` runs the code through the app's key dispatcher, which
compares it against `window.keys` — and the Android device layer
(`stb/android/stb.js`) declares **Android** key codes there:

```
UP 19  DOWN 20  LEFT 21  RIGHT 22  ENTER 66  EXIT/RETURN 4  SETUP 82  INFO 165
PLAY 85  PAUSE 85  STOP 86  NEXT 87  PREV 88  RW 89  FF 90
MUTE 91  VOL_UP 24  VOL_DOWN 25  N0..N9 7..16  RED 183 GREEN 184 YELLOW 185 BLUE 186
CH_UP 167  CH_DOWN 168
```

The phone `MainActivity` translated D-pad/OK/BACK into *browser* codes
(38/40/37/39/13/27/80). With the Android device layer loaded, `keys.UP` is 19,
so an injected 38 matched nothing: every D-pad press was consumed and ignored.
The TV build forwards real Android codes and only translates what genuinely
differs:

| Android key | sent to the app | why |
|---|---|---|
| `DPAD_UP/DOWN/LEFT/RIGHT` (19–22) | same | already the device-layer codes |
| `DPAD_CENTER` (23), `ENTER` (66), `NUMPAD_ENTER`, gamepad `BUTTON_A` | 66 | `keys.ENTER` |
| `BACK` (4), `ESCAPE`, gamepad `BUTTON_B` | 4 | `keys.RETURN` / `keys.EXIT` |
| `CHANNEL_UP` (166) / `CHANNEL_DOWN` (167) | 167 / 168 | device layer is one step off Android's own codes; CH+ must go up |
| `MEDIA_PLAY` (126), `MEDIA_PAUSE` (127), `MEDIA_PLAY_PAUSE` (85) | 85 | `keys.PLAY` / `keys.PAUSE` |
| `MENU` (82) | 82 | `keys.SETUP` / `keys.TOOLS` |
| `INFO`, `PROG_*`, `MUTE`, `VOLUME_*`, `MEDIA_STOP/NEXT/PREVIOUS/REWIND/FAST_FORWARD`, digits | same | already the device-layer codes |
| `HOME`, `GUIDE`, TV/input keys | not consumed | belong to the TV system |

Because every key is consumed only while the WebView is ready, early-boot
presses fall through to Android instead of being swallowed.

`BACK` closes whatever overlay is open (the app's `keys.RETURN` branch); two
`BACK` presses within 1.5 s exit the app, so a remote without a dedicated exit
key is never stuck.

## 4. Device layer override

`overrides/stb/android/stb.js` is copied over the staged payload. It keeps the
shared key table and version tag (`version += " android-0219"`) but fixes a boot
failure present in the shared file:

```js
// OTT-X/stb/android/stb.js (also dune/, mag/, spark/)
var _baseStbInit = typeof stbInit === "function" ? stbInit : function () {};
function stbInit() { _baseStbInit(); ... }        // <-- infinite recursion
```

Function declarations hoist, so `_baseStbInit` captured the new `stbInit` and
every boot logged `RangeError: Maximum call stack size exceeded at stbInit`
(reproduced in the browser boot of the built payload, then gone after the
override). `stb/lg/webos/stb.js` already carries the fix:

```js
function webosStbInit() { _baseStbInit(); ... }
window.stbInit = webosStbInit;   // explicit assignment, no hoisting
```

The override mirrors that pattern. `npm run verify` fails if a
`function stbInit()` declaration ever reappears in the payload.

## 5. Findings in the shared checkout (not modified here)

1. `stb/android/stb.js` (and `stb/dune`, `stb/mag`, `stb/spark`) — the
   hoisting recursion of §4.
2. `android/app/src/main/java/play/ott/foss/MainActivity.java` (phone project)
   never calls `registerPlugin(...)`. Capacitor only auto-loads plugins listed
   in `assets/capacitor.plugins.json`, which the CLI generates from **npm**
   plugins only (`npx cap sync` reports "Found 1 Capacitor plugin … @capacitor/app").
   So `DashExoPlayer`, `M3UProxy`, `MobileCommandQueue`, `MobileNativeMedia`,
   `StalkerPortal` and `MobileXmltvEpg` are never registered and their JS
   callers fall through to the web fallbacks. This project registers all six.
3. The phone project ships no `res/values/colors.xml` while `styles.xml`
   references `@color/colorPrimary`, `@color/colorPrimaryDark` and
   `@color/colorAccent` → `aapt2` fails with "resource color/colorPrimary not
   found". Fixed here; left untouched in `../OTT-X`.
4. `webDir: "dist"` in the phone `capacitor.config.ts` gives the wrong asset
   layout (§1).

## 6. Verified / not verified

Verified here (no Android SDK needed):

* `npm run web:build` → 25 modules, `www/dist/stbPlayer.js` **md5-identical** to
  the LG build (`e314160b…`, 555,835 bytes), so the TV payload carries the same
  frontend, including the live-resume fix.
* The shared checkout is byte-untouched by the build (fingerprint check inside
  `build-web.mjs`).
* Browser boot of the staged payload as an Android device
  (`http://127.0.0.1:4180/f/android/`): `ott_device === "android"`,
  `version === "Version: 2.0.0 android-0219"`, `window.keys` = the table in §3,
  `startPlayer`/`_doKey`/jQuery/Hls/shaka all present, boot flag cleared, no
  RangeError after the override.
* Key dispatch path: `_doKey(167)` → `keyHandler` → `handleMainKey` → `plusProg`
  (channel up) and `_doKey(22)` → right/`popupList`, i.e. the codes in §3 reach
  the app's own actions.
* `npx cap sync android` succeeds; `assets/public/` has the §1 layout.
* `npm run verify` — 83/83 checks.

Not verified (blocked, no JDK/Android SDK/`ANDROID_HOME` on this machine):

* Gradle build of the APK/AAB — the scaffold is Capacitor-8/AGP 8.13/Gradle
  8.14.3/Java-21 consistent with the generated project, but it has never been
  compiled.
* Real hardware/emulator run: launch from the TV rail, banner rendering, D-pad
  and channel rocker behaviour, Leanback focus, `MediaPlaybackService`
  notification, playback of a configured playlist.
* Play Store TV listing checks, DRM playback, and the native DASH/ExoPlayer path.
