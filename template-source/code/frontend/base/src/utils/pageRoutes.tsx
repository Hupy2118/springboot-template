import { lazy, Suspense, type ReactNode } from 'react';
import type { RouteObject } from 'react-router-dom';
import { normalizePageModuleImportPath, type ResolvedPageRoute } from '@/utils/pageRouteTree';

export type PageElementWrapper = (element: ReactNode, item: ResolvedPageRoute) => ReactNode;

type PageModule = { default: React.ComponentType<any> };

// Rspack 在编译期发现页面；lazy 保持每个业务页面按需加载。
const pageContext = import.meta.webpackContext<PageModule>('../pages', {
  recursive: true,
  regExp: /\/index\.tsx$/,
  mode: 'lazy',
});

const pageModules = Object.fromEntries(
  pageContext.keys()
    .filter((request) => !/^\.\/(?:Login|Logout)\//.test(request))
    .map((request) => [
      normalizePageModuleImportPath(`src/pages/${request.replace(/^\.\//, '')}`),
      () => pageContext(request),
    ]),
) as Record<string, () => Promise<PageModule>>;
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
