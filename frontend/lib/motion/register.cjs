/* .ts 파일을 require 할 수 있게 하는 훅 (테스트·점검 스크립트용). */
const fs = require('fs');
const ts = require('typescript');
require.extensions['.ts'] = (module, filename) => {
  const out = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText;
  module._compile(out, filename);
};
