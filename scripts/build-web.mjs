/**
 * Web payload builder for the Android TV project.
 *
 * Reproduces the Mode A build pipeline (tsc → concatenate → terser) but writes
 * ONLY inside this project:
 *
 *   <OTT-X-TV>/build/stbPlayer.js   intermediate concatenation
 *   <OTT-X-TV>/www/dist/stbPlayer.js minified bundle  (Capacitor web root)
 *   <OTT-X-TV>/www/index.html        version-substituted shell
 *   <OTT-X-TV>/www/{stb,stbPlayer,js,fonts,prov}/ static runtime assets
 *
 * The shared frontend is read from OTT_SRC (default: ../OTT-X) and is never
 * written to: `tsc` is invoked with --outDir/--rootDir pointing back here.
 *
 * Usage:
 *   node scripts/build-web.mjs
 *   OTT_SRC=/path/to/OTT-X node scripts/build-web.mjs
 */

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
    cpSync,
    existsSync,
    mkdirSync,
    readFileSync,
    readdirSync,
    rmSync,
    statSync,
    writeFileSync,
} from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { minify } from "terser";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const OTT_SRC = resolve(process.env.OTT_SRC || join(ROOT, "..", "OTT-X"));
const BUILD_DIR = join(ROOT, "build");
const WWW = join(ROOT, "www");

if (!existsSync(join(OTT_SRC, "src", "index.ts"))) {
    console.error(`[build-web] OTT_SRC does not look like an OTT-X checkout: ${OTT_SRC}`);
    process.exit(1);
}

/** Prefer this project's toolchain, fall back to the shared checkout's binaries. */
function tool(name) {
    const candidates = [join(ROOT, "node_modules", ".bin", name), join(OTT_SRC, "node_modules", ".bin", name)];
    for (const c of candidates) {
        if (existsSync(c)) return c;
    }
    throw new Error(`[build-web] cannot find '${name}' — run npm install in ${ROOT}`);
}

/**
 * The module list is read straight out of the shared vite.config.ts instead of
 * being duplicated here, so the two projects can never drift apart: if the LG
 * pipeline reorders or adds a module, this build picks it up automatically.
 */
function readModuleList() {
    const cfg = readFileSync(join(OTT_SRC, "vite.config.ts"), "utf8");
    const match = cfg.match(/const MODULES = \[([\s\S]*?)\];/);
    if (!match) throw new Error("[build-web] MODULES array not found in OTT-X/vite.config.ts");
    const mods = [...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
    if (mods.length < 20) {
        throw new Error(`[build-web] suspicious MODULES list (${mods.length} entries) — refusing to build`);
    }
    return mods;
}

/**
 * Hard guarantee behind "the two projects never mix": the shared checkout is
 * fingerprinted before and after the build, and the build aborts if anything
 * inside it changed (generated dirs, module order, git index — all of it).
 */
const SKIP_DIRS = new Set([".git", "node_modules", "dist", "build", ".git"].filter(Boolean));

function fingerprintSource() {
    const parts = [];
    // Every tracked/generated artefact that a build could plausibly touch.
    for (const dir of ["build", "dist"]) {
        const abs = join(OTT_SRC, dir);
        if (!existsSync(abs)) {
            parts.push(`${dir}:absent`);
            continue;
        }
        for (const file of walk(abs)) {
            const st = statSync(file);
            const body = readFileSync(file);
            parts.push(
                `${relative(OTT_SRC, file)} ${st.size} ${createHash("md5").update(body).digest("hex")}`
            );
        }
    }
    // …plus the mtime of every other file in the checkout, so a stray write
    // anywhere (a source file, a config, a log) is caught too.
    for (const file of walk(OTT_SRC)) {
        const st = statSync(file);
        parts.push(`${relative(OTT_SRC, file)} ${st.size} ${Math.round(st.mtimeMs)}`);
    }
    try {
        parts.push(
            execFileSync("git", ["-C", OTT_SRC, "status", "--porcelain"], { encoding: "utf8" })
        );
    } catch {
        parts.push("git:unavailable");
    }
    return createHash("sha256").update(parts.join("\n")).digest("hex");
}

function walk(dir) {
    const out = [];
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) {
            if (SKIP_DIRS.has(entry.name)) continue;
            out.push(...walk(full));
        } else if (entry.isFile()) out.push(full);
    }
    return out;
}

const EXPORT_BRACE_RE = /^export\s*\{[^}]*\};?\s*$/;
const EXPORT_RE = /^(\s*)export\s+/;

/** Strip ES module syntax — same logic as OTT-X/vite.config.ts. */
function stripModule(code) {
    return code
        .split("\n")
        .filter((line) => !line.trim().startsWith("import "))
        .map((line) => {
            const t = line.trim();
            if (t.startsWith("export ")) {
                if (EXPORT_BRACE_RE.test(t)) return "// " + line;
                return line.replace(EXPORT_RE, "$1");
            }
            return line;
        })
        .join("\n");
}

/** Static runtime trees the Mode A shell fetches by absolute path. */
const ASSET_TREES = ["stb", "stbPlayer", "js", "fonts", "prov"];

/** Directory whose contents are copied over the staged payload (TV fixes). */
const OVERRIDES = join(ROOT, "overrides");

function stageAssets() {
    for (const dir of ASSET_TREES) {
        const src = join(OTT_SRC, dir);
        if (!existsSync(src)) {
            console.warn(`[build-web] WARN: ${dir}/ missing in ${OTT_SRC}`);
            continue;
        }
        cpSync(src, join(WWW, dir), { recursive: true });
    }
    const favicon = join(OTT_SRC, "favicon.ico");
    if (existsSync(favicon)) cpSync(favicon, join(WWW, "favicon.ico"));
}

/** Copy this project's overlay files over the staged payload. */
function applyOverrides() {
    if (!existsSync(OVERRIDES)) return;
    for (const file of walk(OVERRIDES)) {
        const rel = relative(OVERRIDES, file);
        const dest = join(WWW, rel);
        mkdirSync(dirname(dest), { recursive: true });
        cpSync(file, dest);
        console.log(`[build-web] override applied: ${rel}`);
    }
}

/**
 * The shell requests `/<tree>/<vendor>/stb.js` for the device layer. The TV
 * build keeps every vendor directory (so the same payload still boots as "pc"
 * in a browser or on a phone) but guarantees the android layer is on disk and
 * that it is the non-recursive variant.
 */
function assertDeviceLayer() {
    const layer = join(WWW, "stb", "android", "stb.js");
    if (!existsSync(layer)) throw new Error(`[build-web] missing device layer: ${layer}`);
    const text = readFileSync(layer, "utf8");
    for (const needle of ["var keys", "CH_UP: 167", "CH_DOWN: 168"]) {
        if (!text.includes(needle)) throw new Error(`[build-web] android device layer lost '${needle}'`);
    }
    // Regression guard for the hoisting bug: the wrapper must be assigned, not
    // declared, or _baseStbInit captures itself and boot blows the stack.
    // Comments are stripped first so a file may freely *describe* the bug.
    const code = text
        .replace(/\/\*[\s\S]*?\*\//g, "")
        .replace(/^\s*\/\/.*$/gm, "");
    if (/function\s+stbInit\s*\(/.test(code)) {
        throw new Error(
            "[build-web] android device layer declares `function stbInit()` — " +
                "that hoists and recurses forever; assign window.stbInit instead"
        );
    }
    if (!/window\.stbInit\s*=/.test(text)) {
        throw new Error("[build-web] android device layer does not assign window.stbInit");
    }
}

function main() {
    const mods = readModuleList();
    console.log(`[build-web] source: ${OTT_SRC}`);
    const sourceBefore = fingerprintSource();
    rmSync(BUILD_DIR, { force: true, recursive: true });
    rmSync(WWW, { force: true, recursive: true });
    mkdirSync(WWW, { recursive: true });

    console.log("[build-web] step 1: tsc (outDir -> ./build, rootDir -> <OTT_SRC>/src)");
    execFileSync(
        tool("tsc"),
        [
            "-p",
            join(OTT_SRC, "tsconfig.json"),
            "--outDir",
            BUILD_DIR,
            "--rootDir",
            join(OTT_SRC, "src"),
        ],
        { cwd: OTT_SRC, stdio: "inherit" }
    );

    console.log(`[build-web] step 2: concatenate (${mods.length} modules)`);
    let bundle = "";
    for (const mod of mods) {
        const full = join(BUILD_DIR, mod.replace(/^build\//, ""));
        if (!existsSync(full)) {
            console.warn(`[build-web] WARN: ${mod} was not emitted by tsc`);
            continue;
        }
        bundle += stripModule(readFileSync(full, "utf8")) + "\n";
    }
    if (bundle.length < 100000) {
        throw new Error(`[build-web] concatenation looks truncated (${bundle.length} bytes)`);
    }

    const pkg = JSON.parse(readFileSync(join(OTT_SRC, "package.json"), "utf8"));
    const version = pkg.version || "local";
    bundle = bundle.replace(/__OTTP_VERSION__/g, version);
    writeFileSync(join(BUILD_DIR, "stbPlayer.js"), bundle);
    console.log(`[build-web] concatenated: ${bundle.length} bytes`);

    console.log("[build-web] step 3: terser");
    // eslint-disable-next-line no-undef
    return minify(bundle, {
        compress: { defaults: false },
        mangle: false,
        module: false,
        output: { comments: false },
    }).then((result) => {
        if (result.error) throw result.error;
        mkdirSync(join(WWW, "dist"), { recursive: true });
        writeFileSync(join(WWW, "dist", "stbPlayer.js"), result.code);
        console.log(`[build-web] minified: ${result.code.length} bytes -> www/dist/stbPlayer.js`);

        const indexSrc = join(OTT_SRC, "index.html");
        const html = readFileSync(indexSrc, "utf8").replace(/__OTTP_VERSION__/g, version);
        writeFileSync(join(WWW, "index.html"), html);
        console.log(`[build-web] www/index.html (version=${version})`);

        console.log("[build-web] step 4: stage static assets");
        stageAssets();
        console.log("[build-web] step 5: apply TV overrides");
        applyOverrides();
        assertDeviceLayer();

        const sourceAfter = fingerprintSource();
        if (sourceAfter !== sourceBefore) {
            throw new Error(
                `[build-web] the shared OTT-X checkout changed during this build (${OTT_SRC}) — ` +
                    "the TV build must be strictly read-only"
            );
        }
        console.log("[build-web] shared checkout untouched (fingerprint verified)");
        console.log(`[build-web] done -> ${WWW}`);
    });
}

main().catch((err) => {
    console.error(err instanceof Error ? err.message : err);
    process.exit(1);
});
