// Keep distributed originals and the generated Vite runtime covered by their licenses.
import { copyFile, mkdir } from 'node:fs/promises';

const destination = new URL('../dist/licenses/', import.meta.url);
await mkdir(destination, { recursive: true });
for (const [source, name] of [
  ['../../LICENSE', 'DJHX_MEMO_LICENSE.txt'],
  ['../../assets/ui-icons/LICENSE.txt', 'lucide.txt'],
  ['../node_modules/vite/LICENSE.md', 'vite.txt'],
]) {
  await copyFile(new URL(source, import.meta.url), new URL(name, destination));
}
