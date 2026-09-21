const PAGE_ID_PATTERN = /^[a-z0-9]+(?:_[a-z0-9]+)*$/;

function assertPagePath(path: string) {
  if (!PAGE_ID_PATTERN.test(path)) {
    throw new Error(`非法内部 path：${path}。内部 path 必须为小写 snake_case。`);
  }
}

/** 将内部 path 转换为 React 页面目录名。 */
export function pageDirectoryFromPath(path: string) {
  assertPagePath(path);
  return path.split('_').map((segment) => segment[0].toUpperCase() + segment.slice(1)).join('');
}

/** 将内部 path 转换为浏览器路由段。 */
export function pageRouteSegmentFromPath(path: string) {
  assertPagePath(path);
  return path.replace(/_/g, '-');
}
