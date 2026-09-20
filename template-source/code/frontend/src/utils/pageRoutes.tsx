import { lazy, Suspense, type ReactNode } from 'react';
import type { RouteObject } from 'react-router-dom';
import type { ResolvedPageRoute } from '@/utils/pageRouteTree';

export type PageElementWrapper = (element: ReactNode, item: ResolvedPageRoute) => ReactNode;

// 使用 Vite 的 import.meta.glob 预扫描所有业务页面（编译时静态分析）。
const pageModules = import.meta.glob([
  '@/pages/**/index.tsx',
  '!@/pages/Login/**/index.tsx',
  '!@/pages/Logout/**/index.tsx',
]) as Record<string, () => Promise<{ default: React.ComponentType<any> }>>;
/** 从标准化页面树生成 React Router 路由。 */
export function createPageRoutes(
  items: ResolvedPageRoute[],
  wrapElement?: PageElementWrapper,
): RouteObject[] {
  if (!Array.isArray(items) || items.length === 0) return [];

  const result: RouteObject[] = [];
  for (const item of items) {
    if (item.kind === 'external') continue;

    const route: RouteObject = { path: item.routeSegment };
    if (item.kind === 'page') {
      const loader = item.moduleImportPath ? pageModules[item.moduleImportPath] : undefined;
      if (!loader) {
        throw new Error(`未找到页面模块：${item.definition.path}（期望 ${item.moduleImportPath || '有效页面标识'}）`);
      }
      const Page = lazy(loader);
      const element = <Suspense><Page /></Suspense>;
      route.element = wrapElement ? wrapElement(element, item) : element;
    }
    if (item.children.length) route.children = createPageRoutes(item.children, wrapElement);
    result.push(route);
  }
  return result;
}
