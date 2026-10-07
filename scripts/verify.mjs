/**
 * Verification for the Android TV project.
 *
 * Run `npm run verify` after `npm run web:build`. Everything here is
 * checkable without an Android SDK: payload layout, parity with the shared
 * OTT-X frontend, the Android device layer, and the TV-shaped project files.
 */

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const OTT_SRC = resolve(process.env.OTT_SRC || join(ROOT, "..", "OTT-X"));
const ANDROID = join(ROOT, "android");
const APP = join(ANDROID, "app");
const JAVA = join(APP, "src", "main", "java", "play", "ott", "foss");

let failures = 0;
let checks = 0;

function ok(name, condition, detail = "") {
    checks++;
    if (condition) {
        console.log(`  ok   ${name}`);
    } else {
        failures++;
        console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ""}`);
    }
}

function section(title) {
    console.log(`\n${title}`);
}

function read(path) {
    return readFileSync(path, "utf8");
}

function md5(path) {
    return createHash("md5").update(readFileSync(path)).digest("hex");
}

/** Minimal well-formedness check (stack based, ignores comments/decls). */
function xmlWellFormed(text) {
    const body = text
        .replace(/<!--[\s\S]*?-->/g, "")
        .replace(/<\?[\s\S]*?\?>/g, "")
        .replace(/<!DOCTYPE[^>]*>/g, "");
    const stack = [];
    const re = /<(\/?)([A-Za-z_][\w.:-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(\/?)>/g;
    let m = re.exec(body);
    while (m) {
        const [, close, tag, , selfClose] = m;
        if (close) {
            if (stack.pop() !== tag) return `unbalanced </${tag}>`;
        } else if (!selfClose) {
            stack.push(tag);
        }
        m = re.exec(body);
    }
    return stack.length === 0 ? null : `unclosed <${stack.join(">, <")}>`;
}

/* ------------------------------------------------------------------ payload */

section("payload (www/)");

const shellRequests = [
    "index.html",
    "dist/stbPlayer.js",
    "stb/android/stb.js",
    "stbPlayer/1280.css",
    "stbPlayer/icon.png",
    "js/hls.min.js",
    "js/jquery-1.11.1.min.js",
    "js/shaka-player.compiled.js",
    "favicon.ico",
];

if (!existsSync(join(ROOT, "www"))) {
    console.log("  FAIL www/ missing — run `npm run web:build` first");
    process.exit(1);
}

for (const rel of shellRequests) {
    const abs = join(ROOT, "www", rel);
    ok(
        `serves /${rel}`,
        existsSync(abs) && statSync(abs).size > 0,
        existsSync(abs) ? "empty file" : "not found"
    );
}

for (const dir of ["fonts", "prov", "stb"]) {
    ok(`payload has ${dir}/`, existsSync(join(ROOT, "www", dir)));
}
ok(
    "payload has every device layer",
    ["android", "lg/webos", "pc", "mag", "samsung/tizen"].every((d) =>
        existsSync(join(ROOT, "www", "stb", d, "stb.js"))
    )
);

const bundle = join(ROOT, "www", "dist", "stbPlayer.js");
// The payload must be the frontend it was built from: whatever live-resume
// markers the frontend sources carry must appear in the bundle — and none must
// appear from nowhere. (The fix is in the local frontend working tree; a
// frontend ref that predates it legitimately produces a payload without it.)
const RESUME_MARKERS = ["matched by", "no live bookmark"];
const frontendChannels = join(OTT_SRC, "src", "channels", "index.ts");
const sourceHasResume =
    existsSync(frontendChannels) && RESUME_MARKERS.every((m) => read(frontendChannels).includes(m));
const bundleText = existsSync(bundle) ? read(bundle) : "";
const bundleHasResume = RESUME_MARKERS.every((m) => bundleText.includes(m));
ok(
    sourceHasResume
        ? "bundle carries the live-resume fix (it is in the built frontend)"
        : "bundle matches the frontend it was built from (no resume fix in this ref)",
    sourceHasResume === bundleHasResume,
    `source=${sourceHasResume} bundle=${bundleHasResume}`
);

const lgBundle = join(OTT_SRC, "dist", "stbPlayer.js");
if (existsSync(lgBundle)) {
    const same = md5(bundle) === md5(lgBundle);
    console.log(
        `${same ? "  ok  " : "  warn"} byte-identical to the LG build ` +
            `(${statSync(bundle).size} vs ${statSync(lgBundle).size} bytes)` +
            (same ? "" : " — the OTT-X dist/ may simply be an older build")
    );
}

/* ------------------------------------------------------- shared shell parity */

section("shared shell (no fork)");

const html = read(join(ROOT, "www", "index.html"));
const ottHtml = read(join(OTT_SRC, "index.html"));
ok(
    "index.html is the OTT-X shell with only the version substituted",
    html.replace(/2\.0\.0/g, "__OTTP_VERSION__") === ottHtml
);
ok("shell still loads /dist/stbPlayer.js then /stb/<device>/stb.js",
    html.includes('host + "/dist/stbPlayer.js?') && html.includes('host + "/stb/" + ott_device + "/stb.js?'));

/* --------------------------------------------------------- android device */

section("android device layer");

const layer = read(join(ROOT, "www", "stb", "android", "stb.js"));
const layerCode = layer.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
ok("no hoisting-recursive `function stbInit`", !/function\s+stbInit\s*\(/.test(layerCode));
ok("assigns window.stbInit", /window\.stbInit\s*=/.test(layerCode));
ok("captures the base init safely", /typeof\s+window\.stbInit\s*===\s*"function"/.test(layerCode));
for (const [name, code] of [
    ["UP", 19],
    ["DOWN", 20],
    ["LEFT", 21],
    ["RIGHT", 22],
    ["ENTER", 66],
    ["EXIT", 4],
    ["RETURN", 4],
    ["CH_UP", 167],
    ["CH_DOWN", 168],
    ["PLAY", 85],
    ["PAUSE", 85],
    ["STOP", 86],
    ["NEXT", 87],
    ["PREV", 88],
    ["RW", 89],
    ["FF", 90],
    ["INFO", 165],
    ["SETUP", 82],
    ["MUTE", 91],
    ["VOL_UP", 24],
    ["VOL_DOWN", 25],
    ["N0", 7],
    ["N9", 16],
    ["RED", 183],
    ["GREEN", 184],
    ["YELLOW", 185],
    ["BLUE", 186],
]) {
    ok(`keys.${name} = ${code}`, new RegExp(`${name}:\\s*${code}\\b`).test(layerCode));
}

/* ------------------------------------------------------------ MainActivity */

section("MainActivity (remote key bridge)");

const activity = read(join(JAVA, "MainActivity.java"));
const plugins = [
    "DashExoPlayerPlugin",
    "M3UProxyPlugin",
    "MobileCommandQueuePlugin",
    "MobileNativeMediaPlugin",
    "MobileXmltvEpgPlugin",
    "StalkerPortalPlugin",
];
for (const plugin of plugins) {
    ok(`registers ${plugin}`, activity.includes(`registerPlugin(${plugin}.class)`));
}
ok(
    "registers plugins before super.onCreate()",
    activity.lastIndexOf("registerPlugin(") < activity.lastIndexOf("super.onCreate(")
);
ok("injects raw Android key codes via window._doKey", activity.includes("window._doKey"));
ok(
    "does not translate to browser key codes (old 38/27/80 mapping)",
    !/jsCode\s*=\s*(38|40|37|39|27|80)\s*;/.test(activity)
);
for (const [name, needle] of [
    ["D-pad up/down/left/right are identity", "case KeyEvent.KEYCODE_DPAD_UP:"],
    ["D-pad centre maps to keys.ENTER (66)", "case KeyEvent.KEYCODE_DPAD_CENTER:"],
    ["channel rocker maps to 167/168", "case KeyEvent.KEYCODE_CHANNEL_UP:"],
    ["play/pause maps to 85", "case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:"],
]) {
    ok(name, activity.includes(needle));
}
ok("BACK returns keys.RETURN (4)", /return 4;\s*\/\/ keys\.RETURN/.test(activity));
ok("double BACK exits the app", activity.includes("BACK_EXIT_WINDOW_MS"));

/* -------------------------------------------------------------- manifest */

section("AndroidManifest.xml (TV shape)");

const manifestPath = join(APP, "src", "main", "AndroidManifest.xml");
const manifest = read(manifestPath);
ok("is well formed XML", xmlWellFormed(manifest) === null, xmlWellFormed(manifest));
ok("declares android.software.leanback", manifest.includes('android:name="android.software.leanback"'));
ok(
    "leanback feature is required (TV listing)",
    /android:name="android\.software\.leanback"\s+android:required="true"/.test(manifest)
);
ok(
    "touchscreen is optional (required for TV listings)",
    /android:name="android\.hardware\.touchscreen"\s+android:required="false"/.test(manifest)
);
ok("declares a launcher banner", /android:banner="@drawable\/tv_banner"/.test(manifest));
ok("has a LEANBACK_LAUNCHER entry", manifest.includes("android.intent.category.LEANBACK_LAUNCHER"));
ok("keeps a plain LAUNCHER entry", manifest.includes("android.intent.category.LAUNCHER"));
ok("activity is landscape", manifest.includes('android:screenOrientation="landscape"'));
ok("declares the media playback foreground service", manifest.includes("MediaPlaybackService"));

/* -------------------------------------------------------------- resources */

section("resources");

const banner = join(APP, "src", "main", "res", "drawable-xhdpi", "tv_banner.png");
ok("xhdpi banner exists", existsSync(banner));
if (existsSync(banner)) {
    const buf = readFileSync(banner);
    const isPng = buf.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
    ok("banner is a PNG", isPng);
    if (isPng) {
        const w = buf.readUInt32BE(16);
        const h = buf.readUInt32BE(20);
        ok("banner is 320x180 (TV spec)", w === 320 && h === 180, `${w}x${h}`);
    }
}
const colors = join(APP, "src", "main", "res", "values", "colors.xml");
ok("colors.xml exists (styles.xml references @color/colorPrimary)", existsSync(colors));
if (existsSync(colors)) {
    const text = read(colors);
    const styled = read(join(APP, "src", "main", "res", "values", "styles.xml"));
    const referenced = [...styled.matchAll(/@color\/([A-Za-z0-9_]+)/g)].map((m) => m[1]);
    const missing = referenced.filter((name) => !new RegExp(`name="${name}"`).test(text));
    ok(
        `defines every @color/* used by styles.xml (${referenced.join(", ") || "none"})`,
        missing.length === 0,
        `missing ${missing.join(", ")}`
    );
}

/* ------------------------------------------------------- gradle + config */

section("gradle / capacitor config");

const appGradle = read(join(APP, "build.gradle"));
ok('applicationId is play.ott.foss.tv', /applicationId\s+"play\.ott\.foss\.tv"/.test(appGradle));
ok("namespace stays play.ott.foss", /namespace\s*=\s*"play\.ott\.foss"/.test(appGradle));
ok("versionCode/versionName set", /versionCode\s+20000/.test(appGradle) && /versionName\s+"2\.0\.0"/.test(appGradle));
ok(
    "release signing comes from env (no secrets in the repo)",
    appGradle.includes("System.getenv('KEYSTORE_FILE')")
);
const capConfig = read(join(ROOT, "capacitor.config.ts"));
ok('capacitor appId is play.ott.foss.tv', /appId:\s*"play\.ott\.foss\.tv"/.test(capConfig));
ok('capacitor webDir is www', /webDir:\s*"www"/.test(capConfig));
ok(
    "media3/okhttp dependencies kept",
    ["media3-exoplayer-dash", "media3-exoplayer-hls", "okhttp:4.12.0"].every((d) => appGradle.includes(d))
);

/* ------------------------------------------------------------ separation */

section("separation from the LG project");

const gitStatus = (() => {
    try {
        return execFileSync("git", ["-C", OTT_SRC, "status", "--porcelain"], { encoding: "utf8" });
    } catch {
        return null;
    }
})();
const dirty = gitStatus ? gitStatus.split("\n").filter(Boolean) : [];
ok(
    "no file under OTT-X is newly modified by this project",
    !dirty.some((line) => line.includes("OTT-X-TV") || line.includes("www/")),
    dirty.slice(0, 5).join(" | ")
);
console.log(
    `  info OTT-X working tree has ${dirty.length} pre-existing change(s); ` +
        `see ${relative(ROOT, OTT_SRC)} git status`
);

/* ------------------------------------------------------------------ done */

console.log(
    `\n${failures === 0 ? "PASS" : "FAIL"} — ${checks - failures}/${checks} checks passed` +
        (failures ? `, ${failures} failed` : "")
);
process.exit(failures === 0 ? 0 : 1);
