import vectors from './pageIdentity.vectors.json';

const PAGE_ID_PATTERN = /^[a-z0-9]+(?:_[a-z0-9]+)*$/;

function assertPageId(pageId: string) {
  if (!PAGE_ID_PATTERN.test(pageId)) {
    throw new Error(`非法 pageId：${pageId}。pageId 必须为小写 snake_case。`);
  }
}

/** 将 DevAgentStudio pageId 转换为 React 页面目录名。 */
export function pageDirectoryFromId(pageId: string) {
  assertPageId(pageId);
  return pageId.split('_').map((segment) => segment[0].toUpperCase() + segment.slice(1)).join('');
}

/** 将 DevAgentStudio pageId 转换为业务路由段。 */
export function pageRouteSegmentFromId(pageId: string) {
  assertPageId(pageId);
  return pageId.replace(/_/g, '-');
}

/**
 * 该向量同时由 Node Route Projector 消费，避免 Runtime 与 Projector 的页面身份规则漂移。
 * 它在模块加载时执行；模板更新映射规则时必须同步更新向量。
 */
function verifyPageIdentityVectors() {
  for (const vector of vectors.valid) {
    if (pageDirectoryFromId(vector.pageId) !== vector.pageDirectory
        || pageRouteSegmentFromId(vector.pageId) !== vector.routeSegment) {
      throw new Error(`pageId 映射向量不一致：${vector.pageId}。`);
    }
  }
  for (const invalid of vectors.invalid) {
    try {
      pageDirectoryFromId(invalid);
      throw new Error(`非法 pageId 映射向量未被拒绝：${invalid}。`);
    } catch (error) {
      if (error instanceof Error && error.message.startsWith('非法 pageId 映射向量未被拒绝')) throw error;
    }
  }
}

verifyPageIdentityVectors();
