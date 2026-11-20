import { lstat, readFile } from 'node:fs/promises';
import path from 'node:path';

const marker = 'DEVAGENTSTUDIO:CAPABILITY:';
export function normalizeAgents(bytes) {
  const value = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes).replace(/\r\n?/g, '\n');
  if (/[\x00-\x08\x0b-\x1f\x7f\ufeff]/u.test(value) || !value.replace(/[ \t\n]+$/u, '')) throw new Error('INVALID_AGENTS_SOURCE: illegal or empty text');
  return `${value.replace(/[ \t\n]+$/u, '')}\n`;
}
export function agentsFragment(id, bytes) {
  if (!/^[a-z0-9][a-z0-9_-]*$/.test(id)) throw new Error(`INVALID_AGENTS_SOURCE: invalid id ${id}`);
  const value = normalizeAgents(bytes);
  const begin = `<!-- ${marker}${id}:BEGIN -->\n`;
  const end = `<!-- ${marker}${id}:END -->\n`;
  if (!value.startsWith(begin) || !value.endsWith(end) || value.split(marker).length !== 3 || !value.slice(begin.length, -end.length).startsWith('## ')) {
    throw new Error(`INVALID_AGENTS_SOURCE: incomplete or duplicate markers ${id}`);
  }
  return value;
}
export function composeAgents(base, fragments) {
  let value = normalizeAgents(base);
  if (value.includes(marker)) throw new Error('INVALID_AGENTS_SOURCE: Base contains capability markers');
  const seen = new Set();
  for (const { id, content } of fragments) {
    if (seen.has(id)) throw new Error(`INVALID_AGENTS_SOURCE: duplicate capability ${id}`);
    seen.add(id);
    value += `\n${agentsFragment(id, content)}`;
  }
  return value;
}
const treePlaceholder = '<!-- DEVAGENTSTUDIO:FRONTEND_PROJECT_TREE -->';
// Paths are relative to frontend; both renderers use UTF-16 lexical ordering.
export function frontendProjectTree(files) {
  const root = new Map();
  for (const file of files) {
    if (typeof file !== 'string' || /[\\\x00-\x1f\x7f]/u.test(file) || file.split('/').some((part) => !part || part === '.' || part === '..')) {
      throw new Error('INVALID_AGENTS_SOURCE: unsafe project path');
    }
    if (!/^(AGENTS\.md|package\.json|pnpm-lock\.yaml|rsbuild\.config\.ts)$/.test(file)
      && !/^src\/(App\.tsx|index\.tsx|(?:apis|pages|components|hooks|constants|typings|context|config|routes|platform|providers|layout|bootstrap|observability|utils|extensions)\/.+\.tsx?)$/.test(file)) continue;
    if (file.split('/').some((part) => ['__tests__', 'tests', 'assets', 'public'].includes(part)) || /\.(test|spec)\.tsx?$/.test(file)) continue;
    let node = root;
    for (const part of file.split('/')) {
      if (!node.has(part)) node.set(part, new Map());
      node = node.get(part);
    }
  }
  let out = '```text\nfrontend/\n';
  const walk = (node, prefix) => {
    const entries = [...node.keys()].sort();
    entries.forEach((name, index) => {
      const children = node.get(name);
      const last = index === entries.length - 1;
      out += `${prefix}${last ? '└── ' : '├── '}${name}${children.size ? '/' : ''}\n`;
      walk(children, prefix + (last ? '    ' : '│   '));
    });
  };
  walk(root, '');
  return out + '```';
}
export function composeFrontend(base, fragments, files) {
  // Preserve all source validation before consuming the internal controls.
  composeAgents(base, fragments);
  let value = normalizeAgents(base);
  if (value.split(treePlaceholder).length !== 2) throw new Error('INVALID_AGENTS_SOURCE: expected one frontend tree placeholder');
  value = value.replace(treePlaceholder, () => frontendProjectTree(files));
  for (const { id, content } of fragments) {
    const fragment = agentsFragment(id, content);
    const begin = `<!-- ${marker}${id}:BEGIN -->\n`;
    const end = `<!-- ${marker}${id}:END -->\n`;
    value += `\n${normalizeAgents(new TextEncoder().encode(fragment.slice(begin.length, -end.length)))}`;
  }
  if (value.includes('DEVAGENTSTUDIO:FRONTEND_PROJECT_TREE')) throw new Error('INVALID_AGENTS_SOURCE: unexpected frontend tree placeholder');
  return value;
}
async function readSource(root, relative, optional = false) {
  const target = path.resolve(root, relative);
  if (!target.startsWith(`${path.resolve(root)}${path.sep}`)) throw new Error('INVALID_AGENTS_SOURCE: unsafe path');
  let cursor = path.resolve(root);
  for (const part of ['', ...relative.split('/')]) {
    cursor = part ? path.join(cursor, part) : cursor;
    let info;
    try { info = await lstat(cursor); } catch (error) {
      if (optional && cursor === target && error.code === 'ENOENT') return undefined;
      throw error;
    }
    if (info.isSymbolicLink() || (cursor === target ? !info.isFile() : !info.isDirectory())) throw new Error(`INVALID_AGENTS_SOURCE: unsafe source ${cursor}`);
  }
  return readFile(target);
}
export async function assembleAgents(root, selected, files) {
  const base = await readSource(root, 'base/AGENTS.md');
  const fragments = [];
  for (const { id } of selected) {
    if (!/^[a-z0-9][a-z0-9_-]*$/.test(id)) throw new Error('INVALID_AGENTS_SOURCE: invalid capability id');
    const content = await readSource(root, `extensions/${id}/AGENTS.fragment.md`, !['login', 'authorization'].includes(id));
    if (content !== undefined) fragments.push({ id, content });
  }
  return composeFrontend(base, fragments, files);
}
