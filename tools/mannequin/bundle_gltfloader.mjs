import * as esbuild from 'esbuild';
await esbuild.build({ entryPoints: ['gltfloader_entry.js'], bundle: true, minify: true, format: 'iife', outfile: 'gltfloader.min.js', target: 'es2017',
  plugins: [{ name: 'three-global', setup(b) {
    b.onResolve({ filter: /^three$/ }, () => ({ path: 'three', namespace: 'g' }));
    b.onLoad({ filter: /.*/, namespace: 'g' }, () => ({ contents: 'module.exports = window.THREE', loader: 'js' }));
  } }] });
