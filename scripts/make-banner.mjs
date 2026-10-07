/**
 * Generate the Android TV launcher banner.
 *
 * The TV home screen draws `android:banner` from the manifest; Google's spec
 * asks for a 320x180 px image at xhdpi. Generated here (no design tooling
 * needed) so the art is reproducible and can be replaced by real artwork at any
 * time by dropping a file with the same name.
 *
 * Usage: node scripts/make-banner.mjs
 */

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const RES = join(ROOT, "android", "app", "src", "main", "res");

const BACKGROUND = [0x11, 0x11, 0x11];
const ACCENT = [0xfb, 0xc0, 0x2d];
const SCREEN = [0x1c, 0x1c, 0x1c];

/** @returns {Buffer} raw RGB scanlines with filter byte 0 */
function raster(width, height) {
    const px = Buffer.alloc(width * height * 3);
    const put = (x, y, [r, g, b]) => {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        const i = (y * width + x) * 3;
        px[i] = r;
        px[i + 1] = g;
        px[i + 2] = b;
    };
    for (let y = 0; y < height; y++) {
        for (let x = 0; x < width; x++) put(x, y, BACKGROUND);
    }

    // TV body: rounded rectangle with an accent border.
    const bw = Math.round(width * 0.62);
    const bh = Math.round(height * 0.66);
    const bx = Math.round((width - bw) / 2);
    const by = Math.round(height * 0.1);
    const radius = Math.round(height * 0.08);
    const border = Math.max(2, Math.round(height * 0.026));
    for (let y = by; y < by + bh; y++) {
        for (let x = bx; x < bx + bw; x++) {
            const cx = Math.min(Math.max(x, bx + radius), bx + bw - radius);
            const cy = Math.min(Math.max(y, by + radius), by + bh - radius);
            const dx = x - cx;
            const dy = y - cy;
            if (dx * dx + dy * dy > radius * radius) continue;
            const edge =
                x < bx + border ||
                x >= bx + bw - border ||
                y < by + border ||
                y >= by + bh - border;
            put(x, y, edge ? ACCENT : SCREEN);
        }
    }
    // Stand
    const sw = Math.round(bw * 0.34);
    const sh = Math.max(2, Math.round(height * 0.04));
    const sx = Math.round((width - sw) / 2);
    const sy = by + bh;
    for (let y = sy; y < Math.min(height, sy + sh); y++) {
        for (let x = sx; x < sx + sw; x++) put(x, y, ACCENT);
    }

    // Play triangle in the middle of the screen.
    const triH = Math.round(bh * 0.34);
    const triW = Math.round(triH * 0.86);
    const tx = Math.round(bx + bw / 2 - triW / 2);
    const ty = Math.round(by + bh / 2 - triH / 2);
    for (let y = 0; y < triH; y++) {
        const t = 1 - Math.abs((y / (triH - 1)) * 2 - 1); // 0..1..0
        const rowW = Math.max(1, Math.round(triW * t));
        for (let x = 0; x < rowW; x++) put(tx + x, ty + y, ACCENT);
    }

    const raw = Buffer.alloc((width * 3 + 1) * height);
    for (let y = 0; y < height; y++) {
        raw[y * (width * 3 + 1)] = 0;
        px.copy(raw, y * (width * 3 + 1) + 1, y * width * 3, (y + 1) * width * 3);
    }
    return raw;
}

const CRC_TABLE = (() => {
    const table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        table[n] = c;
    }
    return table;
})();

function crc32(buf) {
    let c = 0xffffffff;
    for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body));
    return Buffer.concat([len, body, crc]);
}

function png(width, height) {
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(width, 0);
    ihdr.writeUInt32BE(height, 4);
    ihdr[8] = 8; // bit depth
    ihdr[9] = 2; // truecolour
    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        chunk("IHDR", ihdr),
        chunk("IDAT", deflateSync(raster(width, height), { level: 9 })),
        chunk("IEND", Buffer.alloc(0)),
    ]);
}

const targets = [
    { dir: "drawable-mdpi", size: [160, 90] },
    { dir: "drawable-xhdpi", size: [320, 180] },
];

for (const { dir, size } of targets) {
    const out = join(RES, dir, "tv_banner.png");
    mkdirSync(dirname(out), { recursive: true });
    writeFileSync(out, png(size[0], size[1]));
    console.log(`[make-banner] ${out} (${size[0]}x${size[1]})`);
}
