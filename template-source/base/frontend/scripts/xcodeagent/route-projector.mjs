#!/usr/bin/env node
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const PROTOCOL = 'route-projector.v1';
const PAGE_ID_PATTERN = /^[a-z0-9]+(?:_[a-z0-9]+)*$/;
const ROUTES_RELATIVE_PATH = 'frontend/src/constants/routes.tsx';
const ROUTES_START = '  // XCODEAGENT_BUSINESS_ROUTES_START';
const ROUTES_END = '  // XCODEAGENT_BUSINESS_ROUTES_END';

function fail(message) {
  throw new Error(`Route Projector: ${message}`);
}

export function pageDirectoryFromId(pageId) {
  if (typeof pageId !== 'string' || !PAGE_ID_PATTERN.test(pageId)) {
    fail(`非法 pageId：${String(pageId)}。pageId 必须为小写 snake_case。`);
  }
  return pageId.split('_').map((segment) => segment[0].toUpperCase() + segment.slice(1)).join('');
}

export function parseRouteProjectorInput(text) {
  let input;
  try {
    input = JSON.parse(text);
  } catch {
    fail('stdin 必须是有效 JSON。');
  }

  if (!input || typeof input !== 'object' || Array.isArray(input)) fail('输入必须是对象。');
  if (input.protocol !== PROTOCOL) fail(`protocol 必须为 ${PROTOCOL}。`);
  if (!Array.isArray(input.pages)) fail('pages 必须是数组。');

  const pageIds = new Set();
  return input.pages.map((page, index) => {
    if (!page || typeof page !== 'object' || Array.isArray(page)) fail(`pages[${index}] 必须是对象。`);
    const { pageId, name, resourceKey } = page;
    pageDirectoryFromId(pageId);
    if (pageIds.has(pageId)) fail(`pageId 重复：${pageId}。`);
    pageIds.add(pageId);
    if (typeof name !== 'string' || name.trim() === '') fail(`pages[${index}].name 必须是非空字符串。`);
    if (resourceKey !== undefined && (typeof resourceKey !== 'string' || resourceKey.trim() === '')) {
      fail(`pages[${index}].resourceKey 必须是非空字符串（如提供）。`);
    }
    return resourceKey === undefined ? { pageId, name } : { pageId, name, resourceKey };
  });
}

export function renderBusinessRoutes(pages) {
  return pages.flatMap((page) => {
    const fields = [
      `    name: ${JSON.stringify(page.name)},`,
      `    pageId: ${JSON.stringify(page.pageId)},`,
    ];
    if (page.resourceKey !== undefined) fields.push(`    resourceKey: ${JSON.stringify(page.resourceKey)},`);
    return ['  {', ...fields, '  },'];
  }).join('\n');
}

export function reconcileBusinessRoutes(routesText, pages) {
  const start = routesText.indexOf(ROUTES_START);
  const end = routesText.indexOf(ROUTES_END);
  if (start < 0 || end < 0 || end <= start || routesText.indexOf(ROUTES_START, start + 1) >= 0 || routesText.indexOf(ROUTES_END, end + 1) >= 0) {
    fail(`未找到唯一的业务路由受管区域：${ROUTES_RELATIVE_PATH}。`);
  }
  const afterStart = start + ROUTES_START.length;
  const replacement = pages.length === 0 ? '\n' : `\n${renderBusinessRoutes(pages)}\n`;
  return `${routesText.slice(0, afterStart)}${replacement}${routesText.slice(end)}`;
}

export function apply(workspace, inputText) {
  const pages = parseRouteProjectorInput(inputText);
  for (const page of pages) {
    const entry = resolve(workspace, 'frontend', 'src', 'pages', pageDirectoryFromId(page.pageId), 'index.tsx');
    if (!existsSync(entry)) fail(`页面入口不存在：frontend/src/pages/${pageDirectoryFromId(page.pageId)}/index.tsx。`);
  }

  const routesPath = resolve(workspace, ROUTES_RELATIVE_PATH);
  if (!existsSync(routesPath)) fail(`未找到受管路由文件：${ROUTES_RELATIVE_PATH}。`);
  const current = readFileSync(routesPath, 'utf8');
  const next = reconcileBusinessRoutes(current, pages);
  if (next !== current) writeFileSync(routesPath, next, 'utf8');
  return { status: 'applied', pages: pages.length, changed: next !== current };
}

function main() {
  if (process.argv.length !== 3 || process.argv[2] !== 'apply') {
    fail('用法：route-projector.mjs apply');
  }
  const result = apply(process.cwd(), readFileSync(0, 'utf8'));
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(new URL(import.meta.url).pathname)) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}
