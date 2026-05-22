import { build, context } from 'esbuild';

// Bundles app/src/app.js (which pulls in the shared pure-JS analysis modules
// from ../../src) into a single www/app.js the Android WebView loads. No Node
// builtins are used on this path, so the bundle is browser-safe.
const opts = {
  entryPoints: ['src/app.js'],
  bundle: true,
  outfile: 'www/app.js',
  format: 'iife',
  target: ['es2020'],
  sourcemap: true,
  logLevel: 'info',
};

if (process.argv.includes('--watch')) {
  const ctx = await context(opts);
  await ctx.watch();
  console.log('esbuild: watching for changes...');
} else {
  await build(opts);
  console.log('esbuild: built www/app.js');
}
