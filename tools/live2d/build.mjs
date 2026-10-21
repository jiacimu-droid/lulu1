import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
await build({
  entryPoints: [resolve(here, 'src', 'stage.js')],
  bundle: true,
  minify: true,
  format: 'iife',
  target: ['chrome100'],
  outfile: resolve(here, '..', '..', 'app', 'src', 'main', 'assets', 'live2d', 'renderer.js'),
  sourcemap: false,
  logLevel: 'info',
})
