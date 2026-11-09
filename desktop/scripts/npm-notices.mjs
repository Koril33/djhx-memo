import { readFileSync, readdirSync, writeFileSync, existsSync } from 'node:fs';
import { resolve, basename } from 'node:path';
import { createHash } from 'node:crypto';

// Production packages from the exact lockfile; build-only packages are not shipped.
const root = resolve(import.meta.dirname, '..');
const lock = JSON.parse(readFileSync(resolve(root, 'package-lock.json'), 'utf8'));
const shipped = new Set(
  JSON.parse(readFileSync(resolve(root, 'dist/bundled-packages.json'), 'utf8')),
);
const parts = [
  'djhx-memo — 桌面前端第三方依赖与许可证\n依据提交的 package-lock.json 及 npm ci 后的原始许可证生成。\n',
];
const texts = new Map();
let count = 0;
for (const [path, entry] of Object.entries(lock.packages).sort(([a], [b]) => a.localeCompare(b))) {
  if (!path || entry.dev || entry.link) continue;
  const directory = resolve(root, path);
  if (!existsSync(directory)) throw new Error(`Locked production package missing: ${path}`);
  const pkg = JSON.parse(readFileSync(resolve(directory, 'package.json'), 'utf8'));
  if (!shipped.has(pkg.name)) continue;
  const files = readdirSync(directory).filter((name) =>
    /^(license|licence|copying|notice)([.\-_]|$)/i.test(name),
  );
  if (!files.length) throw new Error(`Upstream license missing: ${pkg.name}`);
  const upstream = typeof pkg.repository === 'string' ? pkg.repository : pkg.repository?.url;
  parts.push(
    `\n${'='.repeat(72)}\n${pkg.name} ${pkg.version}\nSPDX: ${pkg.license ?? entry.license ?? 'see license file'}\nSource: https://www.npmjs.com/package/${pkg.name}/v/${pkg.version}\nUpstream: ${upstream ?? pkg.homepage ?? 'npm registry'}\n`,
  );
  for (const file of files.sort()) {
    const content =
      readFileSync(resolve(directory, file), 'utf8')
        .split(/\r?\n/)
        .map((line) => line.trimEnd())
        .join('\n')
        .trimEnd() + '\n';
    const identifier = createHash('sha256').update(content).digest('hex').slice(0, 16);
    texts.set(identifier, content);
    parts.push(`License file: ${basename(file)} — full text ${identifier}\n`);
  }
  count++;
}
for (const [identifier, content] of [...texts].sort(([a], [b]) => a.localeCompare(b))) {
  parts.push(`\n${'='.repeat(72)}\nLicense text ${identifier}\n\n${content}\n`);
}
writeFileSync(resolve(root, 'THIRD_PARTY_NPM_NOTICES.txt'), parts.join('').trimEnd() + '\n');
process.stdout.write(`Collected ${count} locked production npm packages.\n`);
