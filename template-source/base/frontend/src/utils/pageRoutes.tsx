import { lazy, Suspense, type ReactNode } from 'react';
import type { RouteObject } from 'react-router-dom';
import type { PageRouteDefinition } from '@/typings/routes';
import { pageDirectoryFromId, pageRouteSegmentFromId } from '@/utils/pageIdentity';

export type PageElementWrapper = (element: ReactNode, item: PageRouteDefinition) => ReactNode;

// 使用 Vite 的 import.meta.glob 预扫描所有业务页面（编译时静态分析）。
const pageModules = import.meta.glob([
  '@/pages/**/index.tsx',
  '!@/pages/System/**/index.tsx',
  '!@/pages/Login/**/index.tsx',
  '!@/pages/Logout/**/index.tsx',
]) as Record<string, () => Promise<{ default: React.ComponentType<any> }>>;
const systemPageModules = import.meta.glob('@/pages/System/**/index.tsx') as Record<string, () => Promise<{ default: React.ComponentType<any> }>>;

function resolvePageModule(item: PageRouteDefinition) {
  if (item.pageId) {
    const importPath = `/src/pages/${pageDirectoryFromId(item.pageId)}/index.tsx`;
    return { importPath, loader: pageModules[importPath] };
  }

  if (item.modulePath) {
    const normalizedKey = item.modulePath.replace(/^\//, '').replace(/\/$/, '');
    const importPath = `/src/pages/${normalizedKey}/index.tsx`;
    return { importPath, loader: systemPageModules[importPath] || pageModules[importPath] };
  }

  return undefined;
}

/** 从统一业务配置生成 React Router 路由；无 path 的目录不会生成独立路由。 */
export function createPageRoutes(
  items: PageRouteDefinition[],
  wrapElement?: PageElementWrapper,
): RouteObject[] {
  if (!Array.isArray(items) || items.length === 0) return [];

  const result: RouteObject[] = [];
  for (const item of items) {
    if (item.isUrl) continue;
    if (!item.path && !item.pageId && item.children) {
      result.push(...createPageRoutes(item.children, wrapElement));
      continue;
    }

    const route: RouteObject = { path: item.pageId ? pageRouteSegmentFromId(item.pageId) : item.path };
    if (item.pageId || item.modulePath) {
      const pageModule = resolvePageModule(item);
      if (!pageModule?.loader) {
        throw new Error(`未找到页面模块：${item.pageId || item.modulePath}（期望 ${pageModule?.importPath || '有效页面标识'}）`);
      }
      const Page = lazy(pageModule.loader);
      const element = <Suspense><Page /></Suspense>;
      route.element = wrapElement ? wrapElement(element, item) : element;
    }
    if (item.children?.length) route.children = createPageRoutes(item.children, wrapElement);
    result.push(route);
  }
  return result;
}
