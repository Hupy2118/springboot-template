/**
 * 在 Rsbuild 的默认 JSX 转换前复用共享的源码定位转换器。
 * @param {string} source 当前模块源码
 * @param {object | undefined} inputSourceMap 上一个 loader 传入的 source map
 */
module.exports = function sourcePathLoader(source, inputSourceMap) {
  const callback = this.async();
  const { rootDir } = this.getOptions();
  this.cacheable?.(true);

  import('./sourcePathPlugin.mjs')
    .then(({ transformSourcePath }) =>
      transformSourcePath(source, this.resourcePath, rootDir),
    )
    .then((result) => {
      if (!result) {
        callback(null, source, inputSourceMap);
        return;
      }
      callback(null, result.code, result.map || inputSourceMap);
    })
    .catch((error) => callback(error));
};
