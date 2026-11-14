import { readFileSync } from 'node:fs';

// Resolved at build time: translations and themes never own download destinations.
export function downloads(file = new URL('../downloads.json', import.meta.url)) {
  const data = JSON.parse(readFileSync(file, 'utf8'));
  const version = /^0\.\d+\.\d+$/;
  if (!version.test(data.version)) throw new Error('Invalid release version');
  const suffixes = { windows: 'windows-x64-setup.exe', android: 'android.apk', linux: 'linux-amd64.deb' };
  const values = { MEMO_VERSION: data.version, MEMO_RELEASE_URL: `https://github.com/Koril33/djhx-memo/releases/tag/v${data.version}` };
  for (const [platform, suffix] of Object.entries(suffixes)) {
    const item = data.platforms[platform];
    if (!version.test(item.version) || item.asset !== `djhx-memo-${item.version}-${suffix}`) throw new Error(`Invalid ${platform} release asset`);
    values[`MEMO_${platform.toUpperCase()}_VERSION`] = item.version;
    values[`MEMO_${platform.toUpperCase()}_URL`] = `https://github.com/Koril33/djhx-memo/releases/download/v${item.version}/${item.asset}`;
  }
  return { data, values };
}
