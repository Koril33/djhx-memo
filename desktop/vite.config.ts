import { defineConfig } from 'vitest/config';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { fileURLToPath } from 'node:url';

const workerPackages = new Set<string>();
function collectWorkerPackages() {
  return {
    name: 'worker-package-notices',
    generateBundle(
      _options: unknown,
      bundle: Record<string, { type: string; modules?: Record<string, unknown> }>,
    ) {
      for (const output of Object.values(bundle)) {
        if (output.type !== 'chunk') continue;
        for (const module of Object.keys(output.modules ?? {})) {
          const suffix = module.replaceAll('\\', '/').split('/node_modules/').at(-1);
          if (!suffix || suffix === module) continue;
          const segments = suffix.split('/');
          workerPackages.add(
            segments[0].startsWith('@') ? segments.slice(0, 2).join('/') : segments[0],
          );
        }
      }
    },
  };
}

export default defineConfig({
  resolve: {
    alias: {
      // micromark's browser entity decoder needs a DOM; its default/worker entry
      // is pure data and works inside a module worker in WebView2 and WebKitGTK.
      'decode-named-character-reference': fileURLToPath(
        new URL('./node_modules/decode-named-character-reference/index.js', import.meta.url),
      ),
    },
  },
  worker: { format: 'es', plugins: () => [collectWorkerPackages()] },
  plugins: [
    svelte(),
    {
      name: 'bundled-package-notices',
      generateBundle(_options, bundle) {
        const names = new Set<string>(workerPackages);
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
