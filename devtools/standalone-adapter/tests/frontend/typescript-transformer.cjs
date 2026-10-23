const ts = require('typescript');

// The generated baseline's ts-jest 27 cannot transform TypeScript 5; use the installed compiler.
module.exports = {
  process(source, filename) {
    return { code: ts.transpileModule(source, {
      fileName: filename,
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2020,
        jsx: ts.JsxEmit.ReactJSX,
        esModuleInterop: true,
      },
    }).outputText };
  },
};
