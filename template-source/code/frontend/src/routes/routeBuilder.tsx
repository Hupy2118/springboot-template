import { lazy, Suspense } from 'react';
import type { RouteObject } from 'react-router-dom';
import { appRouteGuards, pageRouteGuards } from '@/capability-extensions/routeGuards';
import type { PageRouteDefinition } from '@/typings/routes';
import { resolvePagePath } from '@/utils/route';
import { pageDirectoryFromId } from '@/utils/pageIdentity';

const pageModules = import.meta.glob([
  '@/pages/**/index.tsx',
  '!@/pages/Login/**/index.tsx',
  '!@/pages/Logout/**/index.tsx',
]) as Record<string, () => Promise<{ default: React.ComponentType<any> }>>;

function resolvePageModule(page: PageRouteDefinition) {
  const importPath = `/src/pages/${pageDirectoryFromId(page.pageId)}/index.tsx`;
  return { importPath, loader: pageModules[importPath] };
}

export function applyPageRouteGuards(
  page: PageRouteDefinition,
  pageRoute: RouteObject,
): RouteObject {
  return pageRouteGuards.reduceRight<RouteObject>((childRoute, createGuard) => {
    const guard = createGuard(page);
    return guard ? { element: guard.element, children: [childRoute] } : childRoute;
  }, pageRoute);
}

export function applyAppRouteGuards(appRoute: RouteObject): RouteObject {
  return appRouteGuards.reduceRight<RouteObject>((childRoute, createGuard) => {
    const guard = createGuard();
    return guard ? { element: guard.element, children: [childRoute] } : childRoute;
  }, appRoute);
}

/** Builds every main-Layout page through pageId → directory → lazy module. */
export function createPageRoutes(items: PageRouteDefinition[]): RouteObject[] {
  if (!Array.isArray(items) || items.length === 0) return [];

  const routes: RouteObject[] = [];
  for (const page of items) {
    const path = resolvePagePath(page);
    const pageModule = resolvePageModule(page);
    if (!pageModule.loader) {
      throw new Error(`未找到页面模块：${page.pageId}（期望 ${pageModule.importPath}）`);
    }
    const Page = lazy(pageModule.loader);
    routes.push(applyPageRouteGuards(page, {
      path,
      element: <Suspense><Page /></Suspense>,
    }));
  }
  return routes;
}
