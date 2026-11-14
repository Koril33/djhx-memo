import { defineConfig } from 'vite';
import { readFileSync } from 'node:fs';
import { downloads } from './scripts/downloads.mjs';

const manifest = downloads();
const brand = JSON.parse(readFileSync(new URL('./brand.json', import.meta.url), 'utf8'));
manifest.values.BRAND_NAME_ZH = brand['zh-CN'];

export default defineConfig({
  base: './',
  build: { assetsInlineLimit: 0 },
  plugins: [{
    name: 'memo-static-downloads',
    transformIndexHtml(html) {
      return html.replace(/%(?:MEMO_[A-Z_]+|BRAND_NAME_ZH)%/g, (key) => {
        const value = manifest.values[key.slice(1, -1)];
        if (!value) throw new Error(`Unknown download token ${key}`);
        return value;
      });
    },
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'downloads.json', source: JSON.stringify(manifest.data, null, 2) + '\n' });
    },
  }],
});
