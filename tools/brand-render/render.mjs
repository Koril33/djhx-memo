import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import resvg from '@resvg/resvg-js';

const { Resvg } = resvg;
const require = createRequire(import.meta.url);
assert.equal(require('@resvg/resvg-js/package.json').version, '2.6.2');
const root = fileURLToPath(new URL('../../', import.meta.url));
const outputRoot = join(root, '.local/brand-jade-pack');
const source = readFileSync(join(root, 'assets/brand/sources/jade-ivory-v1/master.svg'));
const sha = (body) => createHash('sha256').update(body).digest('hex');
const sourceSha = '1970cef1201bc15581156631df4c403c217626b92f7f30944b7e0d8296771cb4';
assert.equal(sha(source), sourceSha, 'Approved master changed; use a new version and review');
const markup = source.toString('utf8');
const definitions = markup.match(/<defs>([\s\S]*?)<\/defs>/)[1];
const paths = [...markup.matchAll(/<path fill="([^"]+)" d="([^"]+)"\/>/g)];
assert.deepEqual(paths.map((p) => p[1]), ['#F3EFE2', '#14523A']);
const glyph = paths.map((p) => `<path fill="${p[1]}" d="${p[2]}"/>`).join('');
const mono = (color) => `<path fill="${color}" fill-rule="evenodd" d="${paths[0][2]} ${paths[1][2]}"/>`;
const svg = (body) => `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 256 256"><title>DJHX MEMO</title><defs>${definitions}</defs>${body}</svg>\n`;
const squareBackground = '<rect width="256" height="256" fill="url(#jade)"/>';
const roundBackground = '<circle cx="128" cy="128" r="128" fill="url(#jade)"/>';
const app = svg('<rect width="256" height="256" rx="72" fill="url(#jade)"/>' + glyph);
const round = svg(roundBackground + glyph);
// Preserve the previous Android foreground footprint (~58dp), with room inside
// the 66dp safe area; the launcher's 72dp viewport enlarges it relative to the layer.
const adaptive = (body) => `<g transform="translate(128 128) scale(0.85) translate(-128 -128)">${body}</g>`;
const foreground = svg(adaptive(glyph));
const background = svg(squareBackground);
const monochrome = svg(adaptive(mono('#FFFFFF')));
const outputs = new Map();
function put(name, body) {
  assert(!name.startsWith('/') && !name.includes('..') && !name.includes('\\'));
  assert(!outputs.has(name), `Duplicate output ${name}`);
  outputs.set(name, Buffer.isBuffer(body) ? body : Buffer.from(body));
}
function render(markup, width) {
  return new Resvg(markup, { font: { loadSystemFonts: false }, fitTo: { mode: 'width', value: width } }).render();
}
const pngs = new Map();
for (const size of [16, 24, 32, 48, 64, 128, 256, 512, 1024]) {
  const png = render(app, size).asPng();
  pngs.set(size, png);
  put(`assets/brand/png/icon-${size}.png`, png);
}
function ico(sizes) {
  const header = Buffer.alloc(6 + sizes.length * 16);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(sizes.length, 4);
  let offset = header.length;
  const bodies = sizes.map((size, index) => {
    const png = pngs.get(size);
    const entry = 6 + index * 16;
    header[entry] = size === 256 ? 0 : size;
    header[entry + 1] = header[entry];
    header.writeUInt16LE(1, entry + 4);
    header.writeUInt16LE(32, entry + 6);
    header.writeUInt32LE(png.length, entry + 8);
    header.writeUInt32LE(offset, entry + 12);
    offset += png.length;
    return png;
  });
  return Buffer.concat([header, ...bodies]);
}
const windowsIco = ico([16, 24, 32, 48, 64, 128, 256]);
put('assets/brand/master.svg', app);
put('assets/brand/monochrome.svg', svg(mono('#000000')));
for (const [name, size] of [['32x32.png', 32], ['128x128.png', 128], ['128x128@2x.png', 256], ['icon.png', 512]]) {
  put(`desktop/src-tauri/icons/${name}`, pngs.get(size));
}
put('desktop/src-tauri/icons/icon.ico', windowsIco);
const archive = join(root, 'assets/brand/archive/sunrise-0.1.13');
const previous = JSON.parse(readFileSync(join(archive, 'manifest-before.json'), 'utf8'));
for (const name of ['tray-black.png', 'tray-white.png', 'tray-black.rgba', 'tray-white.rgba']) {
  const path = `desktop/src-tauri/icons/${name}`;
  const body = readFileSync(join(archive, 'files', path));
  assert.equal(sha(body), previous.files[path], 'Preserved tray input changed');
  put(path, body);
}
for (const [density, scale] of [['mdpi', 1], ['hdpi', 1.5], ['xhdpi', 2], ['xxhdpi', 3], ['xxxhdpi', 4]]) {
  const layers = [
    ['ic_launcher', app, 48], ['ic_launcher_round', round, 48],
    ['ic_launcher_foreground', foreground, 108], ['ic_launcher_background', background, 108],
    ['ic_launcher_monochrome', monochrome, 108],
  ];
  for (const [name, content, dp] of layers) {
    put(`android/app/src/main/res/mipmap-${density}/${name}.png`, render(content, dp * scale).asPng());
  }
  put(`android/app/src/main/res/drawable-${density}/ic_stat_notify.png`, render(svg(mono('#FFFFFF')), 24 * scale).asPng());
}
for (const api of [26, 33]) {
  const xml = '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    + '  <background android:drawable="@mipmap/ic_launcher_background"/>\n'
    + '  <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
    + (api === 33 ? '  <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>\n' : '')
    + '</adaptive-icon>\n';
  for (const name of ['ic_launcher', 'ic_launcher_round']) put(`android/app/src/main/res/mipmap-anydpi-v${api}/${name}.xml`, xml);
}
function alphaBounds(image) {
  const pixels = image.pixels;
  const bounds = { left: image.width, top: image.height, right: -1, bottom: -1 };
  for (let y = 0; y < image.height; y++) for (let x = 0; x < image.width; x++) {
    if (pixels[(y * image.width + x) * 4 + 3]) {
      bounds.left = Math.min(bounds.left, x); bounds.right = Math.max(bounds.right, x);
      bounds.top = Math.min(bounds.top, y); bounds.bottom = Math.max(bounds.bottom, y);
    }
  }
  return bounds;
}
for (const content of [foreground, monochrome]) {
  const bounds = alphaBounds(render(content, 108));
  assert(bounds.left >= 21 && bounds.top >= 21 && bounds.right < 87 && bounds.bottom < 87, JSON.stringify(bounds));
}
const themed = render(monochrome, 108);
assert.equal(themed.pixels[(54 * 108 + 54) * 4 + 3], 0, 'Ink drop must remain a transparent monochrome cutout');
const back = render(background, 108).pixels;
assert.equal(back[3], 255, 'Adaptive background must cover corners');
put('website/logo.svg', app);
put('website/public/favicon.svg', app);
put('website/public/favicon.ico', windowsIco);
put('website/public/favicon-16.png', pngs.get(16));
put('website/public/favicon-32.png', pngs.get(32));
const appleTouch = render(svg(squareBackground + glyph), 180);
assert.equal(appleTouch.pixels[3], 255, 'Apple touch background must be opaque');
put('website/public/apple-touch-icon.png', appleTouch.asPng());

// A font-free contact sheet stays deterministic across renderer host systems.
const placed = (body, x, y, size) => `<g transform="translate(${x} ${y}) scale(${size / 256})">${body}</g>`;
let sheetBody = '<rect width="1200" height="640" fill="#F7F6F2"/>';
sheetBody += placed('<rect width="256" height="256" rx="72" fill="url(#jade)"/>' + glyph, 32, 32, 248);
sheetBody += placed(roundBackground + glyph, 328, 32, 248);
// Show the actual 72/108 viewport ratio, rather than the full unmasked layer.
sheetBody += `<svg x="624" y="32" width="248" height="248" viewBox="42.666667 42.666667 170.666666 170.666666"><defs><clipPath id="adaptive-mask"><circle cx="128" cy="128" r="85.333333"/></clipPath></defs><g clip-path="url(#adaptive-mask)">${squareBackground}${adaptive(glyph)}</g></svg>`;
sheetBody += '<rect x="920" y="32" width="248" height="248" rx="36" fill="#152E24"/>';
sheetBody += placed(mono('#FFFFFF'), 920, 32, 248);
let x = 40;
for (const size of [16, 24, 32, 48, 64, 128, 256]) {
  sheetBody += placed('<rect width="256" height="256" rx="72" fill="url(#jade)"/>' + glyph, x, 584 - size, size);
  x += size + 38;
}
for (const [i, color] of ['#2F7A5C', '#0A3024', '#F3EFE2', '#14523A'].entries()) {
  sheetBody += `<rect x="${980 + i * 42}" y="464" width="34" height="76" rx="8" fill="${color}" stroke="#D9DFD8"/>`;
}
const sheet = `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="640" viewBox="0 0 1200 640"><title>DJHX MEMO jade ivory platform and size review</title><defs>${definitions}</defs>${sheetBody}</svg>\n`;
put('assets/brand/design-sheet.svg', sheet);
put('assets/brand/design-sheet.png', render(sheet, 1200).asPng());

// Stored ZIP entries with a fixed DOS timestamp avoid host/time-dependent archives.
function crc32(data) {
  let crc = 0xffffffff;
  for (const byte of data) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function zip(entries) {
  let offset = 0;
  const local = [], central = [];
  for (const [name, body] of [...entries].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)) {
    const filename = Buffer.from(name);
    const checksum = crc32(body);
    const head = Buffer.alloc(30);
    head.writeUInt32LE(0x04034b50); head.writeUInt16LE(20, 4); head.writeUInt16LE(33, 12);
    head.writeUInt32LE(checksum, 14); head.writeUInt32LE(body.length, 18); head.writeUInt32LE(body.length, 22);
    head.writeUInt16LE(filename.length, 26);
    local.push(head, filename, body);
    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50); entry.writeUInt16LE(0x0314, 4); entry.writeUInt16LE(20, 6);
    entry.writeUInt16LE(33, 14); entry.writeUInt32LE(checksum, 16);
    entry.writeUInt32LE(body.length, 20); entry.writeUInt32LE(body.length, 24); entry.writeUInt16LE(filename.length, 28);
    entry.writeUInt32LE((0o100644 << 16) >>> 0, 38); entry.writeUInt32LE(offset, 42);
    central.push(entry, filename);
    offset += head.length + filename.length + body.length;
  }
  const directory = Buffer.concat(central), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.size, 8); end.writeUInt16LE(entries.size, 10);
  end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, directory, end]);
}
const files = Object.fromEntries([...outputs].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([name, body]) => [name, sha(body)]));
const manifest = { format_version: 1, profile: 'jade-ivory-v1', source_sha256: sourceSha, renderer: '@resvg/resvg-js@2.6.2', files };
const entries = new Map(outputs);
entries.set('manifest.json', Buffer.from(JSON.stringify(manifest, null, 2) + '\n'));
entries.set('source.svg', source);
const packed = zip(entries);
for (const [name, body] of outputs) {
  const destination = join(outputRoot, 'files', name);
  mkdirSync(dirname(destination), { recursive: true }); writeFileSync(destination, body);
}
mkdirSync(outputRoot, { recursive: true });
writeFileSync(join(outputRoot, 'jade-ivory-v1.zip'), packed);
if (process.argv.includes('--write-pack')) {
  const target = join(root, 'assets/brand/packs/jade-ivory-v1.zip');
  if (existsSync(target)) assert(readFileSync(target).equals(packed), 'Refuse to overwrite a different versioned pack');
  else writeFileSync(target, packed);
}
console.log(JSON.stringify({ outputs: outputs.size, archive_sha256: sha(packed), source_sha256: sourceSha, adaptive_bounds: alphaBounds(render(foreground, 108)) }));
