/**
 * Static server for the built TV payload (www/).
 *
 * Serves the payload exactly the way the Capacitor WebView will see it
 * (`/dist/stbPlayer.js`, `/stb/<vendor>/stb.js`, `/stbPlayer/1280.css`, ...).
 *
 * `/f/<device>/...` is rewritten to `/...` — the shell's own device detector
 * (`detectDevice()`) reads that path prefix first, so
 * http://127.0.0.1:4180/f/android/ boots the payload as the Android device
 * layer without needing an Android-specific user agent. This is how the web
 * payload is verified on a desktop browser.
 *
 * Usage: node scripts/serve-web.mjs [port]
 */

import { createServer } from "node:http";
import { existsSync, readFileSync, statSync } from "node:fs";
import { dirname, extname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const WWW = join(ROOT, "www");
const PORT = Number(process.argv[2] || process.env.PORT || 4180);

const TYPES = {
    ".css": "text/css",
    ".gif": "image/gif",
    ".html": "text/html",
    ".ico": "image/x-icon",
    ".jpeg": "image/jpeg",
    ".jpg": "image/jpeg",
    ".js": "text/javascript",
    ".json": "application/json",
    ".mjs": "text/javascript",
    ".mp4": "video/mp4",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".ts": "text/plain",
    ".woff": "font/woff",
    ".woff2": "font/woff2",
};

function send(res, status, body, headers = {}) {
    res.writeHead(status, { "Cache-Control": "no-store", ...headers });
    res.end(body);
}

const server = createServer((req, res) => {
    let path = decodeURIComponent((req.url || "/").split("?")[0]);
    const forced = path.match(/^\/f\/([^/]+)(\/.*)?$/);
    if (forced) path = forced[2] || "/";
    if (path === "/") path = "/index.html";

    const file = join(WWW, normalize(path).replace(/^(\.\.[/\\])+/, ""));
    if (!file.startsWith(WWW) || !existsSync(file) || statSync(file).isDirectory()) {
        send(res, 404, `404 ${path}`);
        return;
    }
    send(res, 200, readFileSync(file), {
        "Content-Type": TYPES[extname(file).toLowerCase()] || "application/octet-stream",
    });
});

server.listen(PORT, "127.0.0.1", () => {
    console.log(`[web:serve] http://127.0.0.1:${PORT}/            (auto device detection)`);
    console.log(`[web:serve] http://127.0.0.1:${PORT}/f/android/   (forced Android device layer)`);
});
