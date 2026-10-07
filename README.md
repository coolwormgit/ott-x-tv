# OTT-X TV (Android TV)

A **second, independent project** that packages OTT-X for Android TV.

> Separation rule: this directory is its own project. It **reads** the shared
> web frontend from `../OTT-X` (the LG webOS project) and never writes to it —
> the build fingerprints the OTT-X checkout before and after every run and
> aborts if anything changed. Nothing in `OTT-X/` produces this app, and
> nothing here produces the webOS `.ipk`.

| | LG / webOS project | This project |
|---|---|---|
| Path | `../OTT-X` | `./` (own folder, `git init`-able on its own) |
| App id | `com.example.ottplay` (webOS) | `play.ott.foss.tv` (Android) |
| Native shell | webOS app + `stb/lg/webos/stb.js` | Capacitor 8 + `android/` Gradle project |
| Web payload | `dist/` → `.ipk` | `www/` → `assets/public/` → APK/AAB |
| Device layer | `stb/lg/webos/stb.js` | `overrides/stb/android/stb.js` |
| Build | `npm run build` + `deploy.mjs` | `npm run web:build` + `npx cap sync` + Gradle |
| CI | LG `.ipk` pipeline | [.github/workflows/android.yml](.github/workflows/android.yml) → APK/AAB |

The shared TypeScript frontend is *not* copied or forked: both projects compile
the same `../OTT-X/src`, so bug fixes (for example the live-resume fix) reach
the TV app automatically.

## Layout

```
scripts/build-web.mjs   tsc → concat → terser → www/ (+ asset staging + overrides)
scripts/serve-web.mjs   dev server; /f/android/ forces the Android device layer
scripts/verify.mjs      payload / device layer / manifest / resources checks
scripts/make-banner.mjs generates the 320x180 TV launcher banner
overrides/              TV-specific files copied over the staged payload
android/                Capacitor Android project (TV-shaped)
www/                    build output (gitignored) = Capacitor web root
```

## Prerequisites (to produce an APK)

* **JDK 21** (`java -version`) — Capacitor 8 compiles with Java 21
* **Android SDK** with `compileSdk 36` / `build-tools`, and `ANDROID_HOME` set
* Android Studio (or standalone Gradle + `sdkmanager`)
* Node ≥ 18 (used here with Node 22)

None of these are installed on the development machine used for this project, so
the APK could not be compiled here — see *Verified / not verified* in
[docs/android-tv.md](docs/android-tv.md).

## Build

```bash
npm install                 # Capacitor CLI + typescript + terser

# 1. web payload (reads ../OTT-X, writes only here)
npm run web:build           # -> www/

# 2. copy payload into the Android project
npm run cap:sync            # -> android/app/src/main/assets/public/

# 3. APK / AAB (needs the SDK)
npm run apk:debug           # android/app/build/outputs/apk/debug/*.apk
npm run aab:release         # unsigned unless KEYSTORE_* env vars are set
```

Point at a different checkout with `OTT_SRC=/path/to/OTT-X npm run web:build`.

Release signing uses the same environment contract as the LG/phone project:
`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## CI: the APK is built on GitHub

`.github/workflows/android.yml` does the whole thing on every push/PR, on
`v*` tags and on demand: it checks out this repo **and** the shared frontend
(`coolwormgit/ott-x`), builds `www/`, runs `npm run verify`, sets up JDK 21 +
Android SDK 36, syncs Capacitor and runs Gradle.

Artifacts of a successful run:

* `ott-x-tv-debug-apk` — sideloadable debug APK (always);
* `ott-x-tv-release` — signed APK + AAB (when the keystore secrets are set).

Required repository secrets:

* **`FRONTEND_TOKEN`** — read access to the frontend repo. `coolwormgit/ott-x`
  is private, so CI cannot check it out without this (the job stops with that
  instruction if it is missing);
* `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` — only for
  signed release artifacts; without them the debug APK is still built.

Details, including the Play TV listing steps, are in [docs/ci.md](docs/ci.md).

Run it manually with a different frontend ref: *Actions → Android TV build →
Run workflow → frontend_ref*.

## Verify

```bash
npm run verify              # 83 checks, no SDK required
npm run web:serve           # then open http://127.0.0.1:4180/f/android/
```

`npm run verify` checks the payload layout the shell requests, byte-parity of
the minified bundle with the LG build, that the shared shell is unmodified, the
Android device layer (key table + the hoisting fix), the TV manifest entries,
the banner PNG dimensions, the Gradle/app-id settings and that the LG checkout
is untouched.

## Install on the TV

```bash
adb connect <tv-ip>:5555
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The app appears in the Android TV launcher rail under **OTT-X TV**.
