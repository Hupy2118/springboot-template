const PAGE_ID_PATTERN = /^[a-z0-9]+(?:_[a-z0-9]+)*$/;

function assertPageId(pageId: string) {
  if (!PAGE_ID_PATTERN.test(pageId)) {
    throw new Error(`非法 pageId：${pageId}。pageId 必须为小写 snake_case。`);
  }
}

/** 将 XcodeAgent pageId 转换为 React 页面目录名。 */
export function pageDirectoryFromId(pageId: string) {
  assertPageId(pageId);
  return pageId.split('_').map((segment) => segment[0].toUpperCase() + segment.slice(1)).join('');
}

/** 将 XcodeAgent pageId 转换为业务路由段。 */
export function pageRouteSegmentFromId(pageId: string) {
  assertPageId(pageId);
  return pageId.replace(/_/g, '-');
}
