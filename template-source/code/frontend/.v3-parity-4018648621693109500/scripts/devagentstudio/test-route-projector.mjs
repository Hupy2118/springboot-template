#!/usr/bin/env node
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const script = resolve(dirname(fileURLToPath(import.meta.url)), 'route-projector.mjs');
const routesTemplate = `import type { PageRouteDefinition } from '@/typings/routes';

export const PAGE_ROUTES: PageRouteDefinition[] = [
  { path: 'home', label: '首页' },
  // DEVAGENTSTUDIO_BUSINESS_ROUTES_START
  // DEVAGENTSTUDIO_BUSINESS_ROUTES_END
];
`;

function directoryFromId(pageId) {
  return pageId.split('_').map((part) => part[0].toUpperCase() + part.slice(1)).join('');
}

function workspace(pageIds = [], routesText = routesTemplate) {
  const root = mkdtempSync(resolve(tmpdir(), 'route-projector-'));
  mkdirSync(resolve(root, 'frontend/src/constants'), { recursive: true });
  writeFileSync(resolve(root, 'frontend/src/constants/routes.tsx'), routesText);
  for (const pageId of pageIds) {
    const directory = directoryFromId(pageId);
    mkdirSync(resolve(root, 'frontend/src/pages', directory), { recursive: true });
    writeFileSync(resolve(root, 'frontend/src/pages', directory, 'index.tsx'), 'export default function Page() { return null; }\n');
  }
  return root;
}

/** 在测试工作区直接执行 Projector，校验标准输入与输出契约。 */
function apply(root, pages, protocol = 'route-projector.v2', scriptPath = script) {
  return spawnSync(process.execPath, [scriptPath, 'apply'], {
    cwd: root,
    input: JSON.stringify({ protocol, pages }),
    encoding: 'utf8',
  });
}

function routes(root) { return readFileSync(resolve(root, 'frontend/src/constants/routes.tsx'), 'utf8'); }
function output(result) { assert.equal(result.status, 0, result.stderr); return JSON.parse(result.stdout); }

function test(name, body) {
  try {
    body();
    process.stdout.write(`ok - ${name}\n`);
  } catch (error) {
    process.stderr.write(`not ok - ${name}: ${error.stack}\n`);
    process.exitCode = 1;
  }
}

test('v2 输出按输入顺序报告 requested、applied 与 skipped', () => {
  const root = workspace(['portal_home', 'asset_list']);
  try {
    const result = output(apply(root, [
      { pageId: 'portal_home', name: '门户首页' },
      { pageId: 'report_center', name: '报表中心' },
      { pageId: 'asset_list', name: '资产管理', resourceKey: 'PAGE.ASSET_LIST' },
    ]));
    assert.deepEqual(result, {
      status: 'applied',
      requestedPageIds: ['portal_home', 'report_center', 'asset_list'],
      appliedPageIds: ['portal_home', 'asset_list'],
      skippedPageIds: ['report_center'],
    });
    assert.match(routes(root), /path: "portal_home",\n    label: "门户首页",/);
    assert.doesNotMatch(routes(root), /\b(?:pageId|name):/);
    assert.match(routes(root), /resourceKey: "PAGE\.ASSET_LIST",/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('脚本位于中文及空格路径时仍执行入口并输出 JSON', () => {
  const root = workspace(['asset_list']);
  try {
    // 使用独立脚本副本覆盖 file URL 编码路径，同时保留原相对依赖布局。
    const sourceRoot = resolve(realpathSync(root), '模板 空格');
    const scriptPath = resolve(sourceRoot, 'scripts/devagentstudio/route-projector.mjs');
    mkdirSync(dirname(scriptPath), { recursive: true });
    mkdirSync(resolve(sourceRoot, 'src/utils'), { recursive: true });
    writeFileSync(scriptPath, readFileSync(script));
    writeFileSync(resolve(sourceRoot, 'src/utils/pageIdentity.vectors.json'),
      readFileSync(new URL('../../src/utils/pageIdentity.vectors.json', import.meta.url)));
    const result = output(apply(root, [{ pageId: 'asset_list', name: '资产管理' }], 'route-projector.v2', scriptPath));
    assert.deepEqual(result.appliedPageIds, ['asset_list']);
    assert.match(routes(root), /path: "asset_list"/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('v1、非法输入和保留 pageId 在写入前失败', () => {
  const root = workspace(['asset_list']);
  try {
    const before = routes(root);
    assert.notEqual(apply(root, [{ pageId: 'asset_list', name: '资产管理' }], 'route-projector.v1').status, 0);
    assert.notEqual(apply(root, [{ pageId: 'AssetList', name: '资产管理' }]).status, 0);
    assert.notEqual(apply(root, [{ pageId: 'welcome', name: '欢迎页' }]).status, 0);
    assert.equal(routes(root), before);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('全量 reconcile 删除旧页面，保留 ProductPlan 页面顺序并保持幂等', () => {
  const root = workspace(['portal_home', 'asset_list']);
  try {
    output(apply(root, [{ pageId: 'asset_list', name: '资产管理' }, { pageId: 'portal_home', name: '门户首页' }]));
    const once = routes(root);
    assert.ok(once.indexOf('asset_list') < once.indexOf('portal_home'));
    const repeat = output(apply(root, [{ pageId: 'asset_list', name: '资产管理' }, { pageId: 'portal_home', name: '门户首页' }]));
    assert.deepEqual(repeat.appliedPageIds, ['asset_list', 'portal_home']);
    assert.equal(routes(root), once);
    output(apply(root, [{ pageId: 'portal_home', name: '门户首页' }]));
    assert.doesNotMatch(routes(root), /asset_list/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('目录、缺失入口与 symlink 入口统一 skipped，且 CRLF 保持稳定', () => {
  const root = workspace(['asset_list'], routesTemplate.replace(/\n/g, '\r\n'));
  try {
    mkdirSync(resolve(root, 'frontend/src/pages/ReportCenter/index.tsx'), { recursive: true });
    if (process.platform !== 'win32') {
      mkdirSync(resolve(root, 'outside'), { recursive: true });
      writeFileSync(resolve(root, 'outside/index.tsx'), 'export default null;\n');
      mkdirSync(resolve(root, 'frontend/src/pages/PortalHome'), { recursive: true });
      symlinkSync(resolve(root, 'outside/index.tsx'), resolve(root, 'frontend/src/pages/PortalHome/index.tsx'));
    }
    const result = output(apply(root, [
      { pageId: 'asset_list', name: '资产管理' },
      { pageId: 'report_center', name: '报表中心' },
      { pageId: 'portal_home', name: '门户首页' },
    ]));
    assert.deepEqual(result.appliedPageIds, ['asset_list']);
    assert.deepEqual(result.skippedPageIds, ['report_center', 'portal_home']);
    assert.ok(routes(root).includes('\r\n'));
    assert.equal(routes(root).replace(/\r\n/g, '').includes('\n'), false);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('异常 marker 不修改现有 routes.tsx', () => {
  const malformed = routesTemplate.replace('  // DEVAGENTSTUDIO_BUSINESS_ROUTES_END', '');
  const root = workspace(['asset_list'], malformed);
  try {
    const before = routes(root);
    const result = apply(root, [{ pageId: 'asset_list', name: '资产管理' }]);
    assert.notEqual(result.status, 0);
    assert.equal(routes(root), before);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
