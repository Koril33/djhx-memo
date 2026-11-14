import { defineConfig } from 'vite';
import { downloads } from './scripts/downloads.mjs';

const manifest = downloads();

export default defineConfig({
  base: './',
  build: { assetsInlineLimit: 0 },
  plugins: [{
    name: 'memo-static-downloads',
    transformIndexHtml(html) {
      return html.replace(/%MEMO_[A-Z_]+%/g, (key) => {
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
