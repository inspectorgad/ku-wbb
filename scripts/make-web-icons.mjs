// Generates the dashboard's home-screen icons into docs/, and the iPhone
// app's icon into ios/.
//
//   node scripts/make-web-icons.mjs [docsDir]
//
// The mark comes from icon-art.mjs, the same geometry as the Android launcher.
// Rasterizing goes through the Chromium binary's own canvas rather than
// playwright, so this runs anywhere Chromium is on disk and needs no npm
// install; set CHROME to override the path.
import fs from 'fs';
import path from 'path';
import os from 'os';
import zlib from 'zlib';
import { execFileSync } from 'child_process';
import { svgIcon } from './icon-art.mjs';

const DOCS = process.argv[2] || new URL('../docs', import.meta.url).pathname;
const IOS_ICONS = new URL('../ios/App/Assets.xcassets/AppIcon.appiconset', import.meta.url).pathname;
const CHROME = process.env.CHROME
  || ['/opt/pw-browsers/chromium/chrome-linux/chrome',
      '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
      '/usr/bin/chromium', '/usr/bin/google-chrome'].find(p => fs.existsSync(p));
if (!CHROME) throw new Error('no Chromium found; set CHROME to its path');

// 180 is what iOS asks for via apple-touch-icon; 192 and 512 are the manifest
// sizes Chrome wants; the maskable 512 is the one a launcher may crop.
// The 1024 is the iPhone app's icon, the only size iOS asks for; it goes to
// the asset catalogue rather than docs/, and is the one that must carry no
// alpha channel (see stripAlpha).
const TARGETS = [
  ['icon-180.png', 'full', 180, DOCS, false],
  ['icon-192.png', 'full', 192, DOCS, false],
  ['icon-512.png', 'full', 512, DOCS, false],
  ['icon-maskable-512.png', 'maskable', 512, DOCS, false],
  ['icon-1024.png', 'full', 1024, IOS_ICONS, true],
];

const CRC = Int32Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c;
});
const crc32 = (buf) => {
  let c = ~0;
  for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8);
  return ~c >>> 0;
};

function chunk(type, data) {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(data.length, 0);
  head.write(type, 4, 'ascii');
  const tail = Buffer.alloc(4);
  tail.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])), 0);
  return Buffer.concat([head, data, tail]);
}

/**
 * Re-encodes an 8-bit RGBA PNG as 8-bit RGB.
 *
 * App Store Connect rejects an app icon that has an alpha channel at all,
 * transparent or not (ITMS-90717), and a canvas only ever hands back RGBA.
 * The mark is a filled square, so there is nothing to composite and the
 * channel is simply dropped - but this refuses rather than guesses if the
 * art ever gains a transparent pixel, because flattening one against a
 * colour chosen here would be inventing artwork.
 */
function stripAlpha(png, size) {
  if (png[25] !== 6 || png[24] !== 8) throw new Error(`expected 8-bit RGBA, got depth ${png[24]} type ${png[25]}`);
  let idat = [];
  for (let i = 8; i < png.length; ) {
    const len = png.readUInt32BE(i);
    if (png.toString('ascii', i + 4, i + 8) === 'IDAT') idat.push(png.subarray(i + 8, i + 8 + len));
    i += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = size * 4;
  const out = Buffer.alloc(size * (size * 3 + 1));
  let prev = Buffer.alloc(stride);
  for (let y = 0, pos = 0; y < size; y++) {
    const filter = raw[pos++];
    const line = Buffer.from(raw.subarray(pos, pos + stride));
    pos += stride;
    // Undo the scanline filter: PNG encoders pick one per row.
    for (let x = 0; x < stride; x++) {
      const a = x >= 4 ? line[x - 4] : 0, b = prev[x], c = x >= 4 ? prev[x - 4] : 0;
      if (filter === 1) line[x] = (line[x] + a) & 255;
      else if (filter === 2) line[x] = (line[x] + b) & 255;
      else if (filter === 3) line[x] = (line[x] + ((a + b) >> 1)) & 255;
      else if (filter === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        line[x] = (line[x] + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 255;
      } else if (filter !== 0) throw new Error(`unknown PNG filter ${filter}`);
    }
    const base = y * (size * 3 + 1);
    out[base] = 0; // filter None: the rows are small and deflate does the work
    for (let x = 0; x < size; x++) {
      if (line[x * 4 + 3] !== 255) throw new Error('the icon art has a transparent pixel; iOS icons cannot');
      out.set(line.subarray(x * 4, x * 4 + 3), base + 1 + x * 3);
    }
    prev = line;
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 2;  // colour type 2: RGB, no alpha
  return Buffer.concat([
    png.subarray(0, 8),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(out, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// The SVG is checked in too: it is what a browser tab uses, and it is the
// legible version of the art if the PNGs ever need regenerating by hand.
fs.writeFileSync(path.join(DOCS, 'icon.svg'), svgIcon('full') + '\n');
console.log('wrote icon.svg');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kuicons-'));
for (const [file, shape, size, dir, opaque] of TARGETS) {
  // Drawn onto a canvas at exactly the target size and encoded there, the way
  // make-icons.mjs does for the launcher raster. --screenshot was the obvious
  // route and the wrong one: its output is window-size pixels while the layout
  // viewport is that divided by a device scale factor it applies regardless of
  // --force-device-scale-factor, so the mark cropped at 512 and vanished
  // altogether at 180. A canvas has no viewport to disagree with.
  //
  // The encoded bytes come back through --dump-dom, which needs no automation
  // library — just the browser binary.
  const page = path.join(tmp, `${size}-${shape}.html`);
  fs.writeFileSync(page, `<!doctype html><body><pre id="out"></pre><script>
    (async () => {
      const img = new Image();
      img.src = 'data:image/svg+xml;base64,' + btoa(${JSON.stringify(svgIcon(shape, size))});
      await img.decode();
      const cv = document.createElement('canvas');
      cv.width = cv.height = ${size};
      const ctx = cv.getContext('2d');
      ctx.drawImage(img, 0, 0, ${size}, ${size});
      document.getElementById('out').textContent = cv.toDataURL('image/png').split(',')[1];
    })();
  </script></body>`);
  const dom = execFileSync(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    '--virtual-time-budget=5000', '--dump-dom', `file://${page}`,
  ], { stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 }).toString();
  const b64 = dom.match(/<pre id="out">([A-Za-z0-9+/=]*)<\/pre>/)?.[1];
  if (!b64) throw new Error(`no image came back for ${file}`);
  let buf = Buffer.from(b64, 'base64');
  // Chromium can fail by producing nothing rather than by erroring, and an icon
  // that is silently absent is worse than a build that stops.
  if (buf.slice(1, 4).toString() !== 'PNG') throw new Error(`not a png: ${file}`);
  if (opaque) buf = stripAlpha(buf, size);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, file), buf);
  console.log(`wrote ${file} (${size}px, ${buf.length}b${opaque ? ', no alpha' : ''})`);
}
fs.rmSync(tmp, { recursive: true, force: true });
