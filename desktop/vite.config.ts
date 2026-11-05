import { defineConfig } from 'vitest/config';
import { svelte } from '@sveltejs/vite-plugin-svelte';

export default defineConfig({
  plugins: [
    svelte(),
    {
      name: 'bundled-package-notices',
      generateBundle(_options, bundle) {
        const names = new Set<string>();
        for (const output of Object.values(bundle)) {
          if (output.type !== 'chunk') continue;
          for (const module of Object.keys(output.modules)) {
            const suffix = module.replaceAll('\\', '/').split('/node_modules/').at(-1);
            if (!suffix || suffix === module) continue;
            const segments = suffix.split('/');
            names.add(segments[0].startsWith('@') ? segments.slice(0, 2).join('/') : segments[0]);
          }
        }
        this.emitFile({
          type: 'asset',
          fileName: 'bundled-packages.json',
          source: JSON.stringify([...names].sort()),
        });
      },
    },
  ],
  server: { port: 1420, strictPort: true },
  clearScreen: false,
  build: { target: ['es2022', 'chrome105', 'safari15'] },
  test: { include: ['src/**/*.test.ts'] },
});
