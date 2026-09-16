#!/usr/bin/env node
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const script = resolve(dirname(new URL(import.meta.url).pathname), 'route-projector.mjs');
const routesTemplate = `import type { PageRouteDefinition } from '@/typings/routes';

export const PAGE_ROUTES: PageRouteDefinition[] = [
  { name: '欢迎页', pageId: 'welcome' },
  // XCODEAGENT_BUSINESS_ROUTES_START
  // XCODEAGENT_BUSINESS_ROUTES_END
];
`;

function workspace(pageIds = []) {
  const root = mkdtempSync(resolve(tmpdir(), 'route-projector-'));
  mkdirSync(resolve(root, 'frontend/src/constants'), { recursive: true });
  writeFileSync(resolve(root, 'frontend/src/constants/routes.tsx'), routesTemplate);
  for (const pageId of pageIds) {
    const directory = pageId.split('_').map((part) => part[0].toUpperCase() + part.slice(1)).join('');
    mkdirSync(resolve(root, 'frontend/src/pages', directory), { recursive: true });
    writeFileSync(resolve(root, 'frontend/src/pages', directory, 'index.tsx'), 'export default function Page() { return null; }\n');
  }
  return root;
}

function apply(root, pages) {
  return spawnSync(process.execPath, [script, 'apply'], {
    cwd: root,
    input: JSON.stringify({ protocol: 'route-projector.v1', pages }),
    encoding: 'utf8',
  });
}

function routes(root) {
  return readFileSync(resolve(root, 'frontend/src/constants/routes.tsx'), 'utf8');
}

function test(name, body) {
  try {
    body();
    process.stdout.write(`ok - ${name}\n`);
  } catch (error) {
    process.stderr.write(`not ok - ${name}: ${error.stack}\n`);
    process.exitCode = 1;
  }
}

test('注册标准页面和受权限页面，但不生成 import 或 component', () => {
  const root = workspace(['portal_home', 'asset_list']);
  try {
    const result = apply(root, [
      { pageId: 'portal_home', name: '门户首页' },
      { pageId: 'asset_list', name: '资产管理', resourceKey: 'PAGE.ASSET_LIST' },
    ]);
    assert.equal(result.status, 0, result.stderr);
    const output = routes(root);
    assert.match(output, /name: "门户首页",\n    pageId: "portal_home",/);
    assert.match(output, /resourceKey: "PAGE\.ASSET_LIST",/);
    assert.doesNotMatch(output, /import .*AssetList|component:/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('全量 reconcile 会删除不再存在的页面，并且重复执行无变化', () => {
  const root = workspace(['portal_home', 'asset_list']);
  try {
    assert.equal(apply(root, [{ pageId: 'portal_home', name: '门户首页' }, { pageId: 'asset_list', name: '资产管理' }]).status, 0);
    assert.equal(apply(root, [{ pageId: 'portal_home', name: '门户首页' }]).status, 0);
    const once = routes(root);
    assert.doesNotMatch(once, /asset_list/);
    const result = apply(root, [{ pageId: 'portal_home', name: '门户首页' }]);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(JSON.parse(result.stdout).changed, false);
    assert.equal(routes(root), once);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('缺失页面入口和非法 pageId 会失败', () => {
  const root = workspace();
  try {
    assert.notEqual(apply(root, [{ pageId: 'asset_list', name: '资产管理' }]).status, 0);
    assert.notEqual(apply(root, [{ pageId: 'AssetList', name: '资产管理' }]).status, 0);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
