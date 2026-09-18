import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { sourcePathForFile, transformSourcePath } from './sourcePathPlugin.mjs';

const require = createRequire(import.meta.url);
const sourcePathLoader = require('./sourcePathLoader.cjs');

const rootDir = '/workspace/frontend';
const source = `
const View = () => (
  <>
    <Button />
    <Menu.Item />
    <div />
    <Custom data-path="/custom/value" />
    <React.Fragment><span /></React.Fragment>
  </>
);
`;

const result = await transformSourcePath(source, `${rootDir}/src/page/index.tsx`, rootDir);
assert.ok(result?.code, '应返回转换后的 JSX 代码');
assert.match(result.code, /<Button data-path="\/src\/page\/index\.tsx:4:5"/);
assert.match(result.code, /<Menu\.Item data-path="\/src\/page\/index\.tsx:5:5"/);
assert.match(result.code, /<div data-path="\/src\/page\/index\.tsx:6:5"/);
assert.match(result.code, /<Custom data-path="\/custom\/value"/);
assert.doesNotMatch(result.code, /React\.Fragment data-path=/);
assert.match(result.code, /<span data-path="\/src\/page\/index\.tsx:8:21"/);
assert.equal(
  sourcePathForFile('C:\\workspace\\frontend', 'C:\\workspace\\frontend\\src\\page\\index.tsx'),
  '/src/page/index.tsx',
);
assert.equal(await transformSourcePath(source, `${rootDir}/src/page/index.ts`, rootDir), null);

/** 使用最小 Rspack loader 上下文执行转换，验证 Rsbuild 入口复用了相同注入逻辑。 */
const loaderResult = await new Promise((resolve, reject) => {
  sourcePathLoader.call(
    {
      async: () => (error, code, map) => (error ? reject(error) : resolve({ code, map })),
      cacheable: () => undefined,
      getOptions: () => ({ rootDir }),
      resourcePath: `${rootDir}/src/page/index.tsx`,
    },
    source,
  );
});
assert.match(loaderResult.code, /<Button data-path="\/src\/page\/index\.tsx:4:5"/);
assert.ok(loaderResult.map, 'Rsbuild loader 应返回源码映射');

console.log('sourcePathPlugin tests passed');
