import type { CapacitorConfig } from "@capacitor/cli";

/**
 * Android TV target — deliberately a separate project from the LG webOS build.
 *
 * - `appId` differs from the phone/tablet app (`play.ott.foss`), so both can be
 *   installed side by side and published independently.
 * - `webDir` is this project's own `www/`, produced by scripts/build-web.mjs.
 *   Nothing here ever writes into the OTT-X (webOS) checkout.
 * - `server.hostname` + `androidScheme: "https"` keep the same origin shape the
 *   web frontend expects (`https://localhost/dist/stbPlayer.js`).
 */
const config: CapacitorConfig = {
    android: {
        allowMixedContent: true,
        backgroundAudio: true,
        minSdkVersion: 24,
        webContentsDebuggingEnabled: true,
    },
    appId: "play.ott.foss.tv",
    appName: "OTT-X TV",
    plugins: {
        MobileCommandQueue: {
            // command queue auto-starts via plugin.load on native side
        },
    },
    server: {
        androidScheme: "https",
        hostname: "localhost",
    },
    webDir: "www",
};

export default config;
