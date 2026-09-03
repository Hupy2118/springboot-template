import { transformAsync, types as t } from '@babel/core';

const SOURCE_ATTRIBUTE = 'data-path';
const SOURCE_FILE_PATTERN = /\.(jsx|tsx)$/i;

/** 把不同平台的文件路径统一成用于浏览器元数据的正斜杠格式。 */
function normalizeFilePath(value) {
  return String(value || '').replace(/\\/g, '/').replace(/\/+$/, '');
}

/** 计算相对前端根目录的安全源码路径，只允许注入 src 目录内的文件。 */
export function sourcePathForFile(rootDir, fileName) {
  const normalizedRoot = normalizeFilePath(rootDir);
  const normalizedFile = normalizeFilePath(String(fileName || '').split('?')[0]);
  const rootPrefix = `${normalizedRoot}/`;
  if (!normalizedFile.toLowerCase().startsWith(rootPrefix.toLowerCase())) return '';

  const relativePath = normalizedFile.slice(rootPrefix.length);
  return relativePath.startsWith('src/') ? `/${relativePath}` : '';
}

/** 判断 JSX 标签是否为不能接收普通属性的 Fragment。 */
function isFragmentName(name) {
  if (t.isJSXIdentifier(name)) return name.name === 'Fragment';
  return (
    t.isJSXMemberExpression(name) &&
    t.isJSXIdentifier(name.object, { name: 'React' }) &&
    t.isJSXIdentifier(name.property, { name: 'Fragment' })
  );
}

/** 创建只负责追加 data-path 的 Babel 访问器，不改变原有 JSX 转换方式。 */
function createSourcePathBabelPlugin(sourcePath) {
  return {
    name: 'xcode-agent-source-path-babel-plugin',
    visitor: {
      JSXOpeningElement(nodePath) {
        if (isFragmentName(nodePath.node.name)) return;
        const alreadyAnnotated = nodePath.node.attributes.some(
          (attribute) =>
            t.isJSXAttribute(attribute) &&
            t.isJSXIdentifier(attribute.name, { name: SOURCE_ATTRIBUTE }),
        );
        if (alreadyAnnotated) return;

        const location = nodePath.node.loc?.start;
        if (!location) return;
        const attributeValue = `${sourcePath}:${location.line}:${location.column + 1}`;
        nodePath.node.attributes.push(
          t.jsxAttribute(t.jsxIdentifier(SOURCE_ATTRIBUTE), t.stringLiteral(attributeValue)),
        );
      },
    },
  };
}

/** 使用 Babel 保留 JSX/TypeScript 语法，仅注入源码定位属性和对应 source map。 */
export async function transformSourcePath(code, fileName, rootDir) {
  const cleanFileName = String(fileName || '').split('?')[0];
  const sourcePath = sourcePathForFile(rootDir, cleanFileName);
  if (!sourcePath || !SOURCE_FILE_PATTERN.test(cleanFileName)) return null;

  const parserPlugins = cleanFileName.toLowerCase().endsWith('.tsx')
    ? ['typescript', 'jsx']
    : ['jsx'];
  const result = await transformAsync(code, {
    babelrc: false,
    configFile: false,
    filename: cleanFileName,
    parserOpts: { plugins: parserPlugins },
    plugins: [createSourcePathBabelPlugin(sourcePath)],
    sourceFileName: sourcePath,
    sourceMaps: true,
  });

  if (!result?.code) return null;
  return { code: result.code, map: result.map || null };
}

/** 创建开发服务器专用的 Vite 前置转换插件，并保持原 react() 插件配置不变。 */
export function sourcePathPlugin(options = {}) {
  const rootDir = options.rootDir || process.cwd();
  return {
    name: 'xcode-agent-source-path',
    apply: 'serve',
    enforce: 'pre',
    transform(code, id) {
      return transformSourcePath(code, id, rootDir);
    },
  };
}
