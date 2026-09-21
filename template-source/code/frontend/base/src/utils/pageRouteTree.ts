import type { PageRouteDefinition } from '@/typings/routes';
import { pageDirectoryFromPath, pageRouteSegmentFromPath } from '@/utils/pageIdentity';

export type PageRouteKind = 'directory' | 'page' | 'external';

export type ResolvedPageRoute = {
  definition: PageRouteDefinition;
  kind: PageRouteKind;
  routeSegment?: string;
  internalPath?: string;
  menuKey: string;
  moduleImportPath?: string;
  requiredResourceKeys: string[];
  visible: boolean;
  children: ResolvedPageRoute[];
};

const EXTERNAL_PATH_PATTERN = /^https?:\/\//i;

export function isExternalPath(path: string) {
  return EXTERNAL_PATH_PATTERN.test(path);
}

function toInternalPath(rootPath: string, segments: string[]) {
  return `/${[rootPath.replace(/^\/+|\/+$/g, ''), ...segments].join('/')}`;
}

/**
 * 将唯一页面配置标准化为供路由、菜单和授权层共享的静态树。
 * 内部 path 是 snake_case 标识，children 节点只能作为目录。
 */
export function resolvePageRouteTree(
  items: PageRouteDefinition[],
  rootPath: string,
): ResolvedPageRoute[] {
  const walk = (
    nodes: PageRouteDefinition[],
    parentSegments: string[],
    parentResourceKeys: string[],
    parentVisible: boolean,
  ): ResolvedPageRoute[] => nodes.map((definition) => {
    const requiredResourceKeys = definition.resourceKey
      ? [...parentResourceKeys, definition.resourceKey]
      : parentResourceKeys;
    const visible = parentVisible && definition.visible !== false;

    if (isExternalPath(definition.path)) {
      if (definition.children !== undefined) {
        throw new Error(`外链不能包含子节点：${definition.path}`);
      }
      return {
        definition,
        kind: 'external',
        menuKey: definition.path,
        requiredResourceKeys,
        visible,
        children: [],
      };
    }

    const routeSegment = pageRouteSegmentFromPath(definition.path);
    const segments = [...parentSegments, routeSegment];
    const internalPath = toInternalPath(rootPath, segments);
    const childDefinitions = definition.children;
    const isDirectory = childDefinitions !== undefined;
    const children = childDefinitions
      ? walk(childDefinitions, segments, requiredResourceKeys, visible)
      : [];
    const kind: PageRouteKind = isDirectory ? 'directory' : 'page';

    return {
      definition,
      kind,
      routeSegment,
      internalPath,
      menuKey: internalPath,
      moduleImportPath: kind === 'page'
        ? `/src/pages/${pageDirectoryFromPath(definition.path)}/index.tsx`
        : undefined,
      requiredResourceKeys,
      visible,
      children,
    };
  });

  return Array.isArray(items) ? walk(items, [], [], true) : [];
}
