#!/usr/bin/env node
import { closeSync, existsSync, fsyncSync, lstatSync, openSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';
import { basename, dirname, resolve } from 'node:path';

const PROTOCOL = 'route-projector.v2';
const PAGE_ID_PATTERN = /^[a-z0-9]+(?:_[a-z0-9]+)*$/;
const RESERVED_PAGE_IDS = new Set(['welcome', 'authorization_management']);
const ROUTES_RELATIVE_PATH = 'frontend/src/constants/routes.tsx';
const ROUTES_START = '  // XCODEAGENT_BUSINESS_ROUTES_START';
const ROUTES_END = '  // XCODEAGENT_BUSINESS_ROUTES_END';
const WINDOWS_RENAME_RETRY_CODES = new Set(['EACCES', 'EBUSY', 'EPERM']);
const PAGE_IDENTITY_VECTORS = JSON.parse(readFileSync(new URL('../../src/utils/pageIdentity.vectors.json', import.meta.url), 'utf8'));

function fail(message) { throw new Error(`Route Projector: ${message}`); }

export function pageDirectoryFromId(pageId) {
  if (typeof pageId !== 'string' || !PAGE_ID_PATTERN.test(pageId)) {
    fail(`非法 pageId：${String(pageId)}。pageId 必须为小写 snake_case。`);
  }
  return pageId.split('_').map((segment) => segment[0].toUpperCase() + segment.slice(1)).join('');
}

function pageRouteSegmentFromId(pageId) {
  pageDirectoryFromId(pageId);
  return pageId.replace(/_/g, '-');
}

function verifyPageIdentityVectors() {
  for (const vector of PAGE_IDENTITY_VECTORS.valid) {
    if (pageDirectoryFromId(vector.pageId) !== vector.pageDirectory
        || pageRouteSegmentFromId(vector.pageId) !== vector.routeSegment) {
      fail(`pageId 映射向量不一致：${vector.pageId}。`);
    }
  }
  for (const invalid of PAGE_IDENTITY_VECTORS.invalid) {
    try {
      pageDirectoryFromId(invalid);
      fail(`非法 pageId 映射向量未被拒绝：${invalid}。`);
    } catch (error) {
      if (error && error.message.includes('映射向量未被拒绝')) throw error;
    }
  }
}

verifyPageIdentityVectors();

function isPlainObject(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }

function assertOnlyKeys(value, allowed, label) {
  for (const key of Object.keys(value)) if (!allowed.has(key)) fail(`${label} 不允许字段：${key}。`);
}

export function parseRouteProjectorInput(text) {
  let input;
  try { input = JSON.parse(text); } catch { fail('stdin 必须是有效 JSON。'); }
  if (!isPlainObject(input)) fail('输入必须是对象。');
  assertOnlyKeys(input, new Set(['protocol', 'pages']), '输入');
  if (input.protocol !== PROTOCOL) fail(`protocol 必须为 ${PROTOCOL}。`);
  if (!Array.isArray(input.pages)) fail('pages 必须是数组。');

  const pageIds = new Set();
  return input.pages.map((page, index) => {
    if (!isPlainObject(page)) fail(`pages[${index}] 必须是对象。`);
    assertOnlyKeys(page, new Set(['pageId', 'name', 'resourceKey']), `pages[${index}]`);
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

function assertNoReservedPageIds(pages) {
  for (const { pageId } of pages) {
    if (RESERVED_PAGE_IDS.has(pageId)) fail(`业务 pageId 与模板保留 pageId 冲突：${pageId}。`);
  }
}

function entryIsRegularFile(entry) {
  try {
    // lstat deliberately rejects symlinks and Windows junction/reparse-point paths.
    return lstatSync(entry).isFile();
  } catch (error) {
    if (error && error.code === 'ENOENT') return false;
    throw error;
  }
}

function requireRegularFile(path, label) {
  try {
    if (!lstatSync(path).isFile()) fail(`${label} 必须是普通文件且不得为符号链接。`);
  } catch (error) {
    if (error && error.code === 'ENOENT') fail(`未找到${label}。`);
    throw error;
  }
}

export function renderBusinessRoutes(pages, eol = '\n') {
  return pages.flatMap((page) => {
    const fields = [`    name: ${JSON.stringify(page.name)},`, `    pageId: ${JSON.stringify(page.pageId)},`];
    if (page.resourceKey !== undefined) fields.push(`    resourceKey: ${JSON.stringify(page.resourceKey)},`);
    return ['  {', ...fields, '  },'];
  }).join(eol);
}

export function reconcileBusinessRoutes(routesText, pages) {
  const start = routesText.indexOf(ROUTES_START);
  const end = routesText.indexOf(ROUTES_END);
  if (start < 0 || end < 0 || end <= start || routesText.indexOf(ROUTES_START, start + 1) >= 0 || routesText.indexOf(ROUTES_END, end + 1) >= 0) {
    fail(`未找到唯一的业务路由受管区域：${ROUTES_RELATIVE_PATH}。`);
  }
  // Preserve the existing convention so Windows checkout settings do not create a false whole-file change.
  const eol = routesText.includes('\r\n') ? '\r\n' : '\n';
  const afterStart = start + ROUTES_START.length;
  const replacement = pages.length === 0 ? eol : `${eol}${renderBusinessRoutes(pages, eol)}${eol}`;
  return `${routesText.slice(0, afterStart)}${replacement}${routesText.slice(end)}`;
}

function sleep(milliseconds) { Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds); }

function renameWithWindowsRetry(from, to) {
  const delays = [0, 25, 75, 150];
  let lastError;
  for (const delay of delays) {
    if (delay > 0) sleep(delay);
    try {
      renameSync(from, to);
      return;
    } catch (error) {
      lastError = error;
      if (process.platform !== 'win32' || !WINDOWS_RENAME_RETRY_CODES.has(error && error.code)) throw error;
    }
  }
  throw lastError;
}

export function writeRoutesAtomically(routesPath, contents) {
  const directory = dirname(routesPath);
  const temporaryPath = resolve(directory, `.${basename(routesPath)}.xcodeagent-${process.pid}-${Date.now()}-${Math.random().toString(16).slice(2)}.tmp`);
  let descriptor;
  try {
    descriptor = openSync(temporaryPath, 'wx', 0o600);
    writeFileSync(descriptor, contents, 'utf8');
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = undefined;
    // Same-directory rename avoids cross-volume moves and commits only a complete file.
    renameWithWindowsRetry(temporaryPath, routesPath);
  } catch (error) {
    if (descriptor !== undefined) closeSync(descriptor);
    if (existsSync(temporaryPath)) unlinkSync(temporaryPath);
    throw error;
  }
}

export function apply(workspace, inputText) {
  const pages = parseRouteProjectorInput(inputText);
  assertNoReservedPageIds(pages);
  const appliedPages = [];
  const skippedPageIds = [];
  for (const page of pages) {
    const directory = pageDirectoryFromId(page.pageId);
    const entry = resolve(workspace, 'frontend', 'src', 'pages', directory, 'index.tsx');
    if (entryIsRegularFile(entry)) appliedPages.push(page);
    else skippedPageIds.push(page.pageId);
  }

  const routesPath = resolve(workspace, ROUTES_RELATIVE_PATH);
  requireRegularFile(routesPath, `受管路由文件：${ROUTES_RELATIVE_PATH}`);
  const current = readFileSync(routesPath, 'utf8');
  const next = reconcileBusinessRoutes(current, appliedPages);
  if (next !== current) writeRoutesAtomically(routesPath, next);
  return {
    status: 'applied',
    requestedPageIds: pages.map((page) => page.pageId),
    appliedPageIds: appliedPages.map((page) => page.pageId),
    skippedPageIds,
  };
}

function main() {
  if (process.argv.length !== 3 || process.argv[2] !== 'apply') fail('用法：route-projector.mjs apply');
  const result = apply(process.cwd(), readFileSync(0, 'utf8'));
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(new URL(import.meta.url).pathname)) {
  try { main(); } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}
