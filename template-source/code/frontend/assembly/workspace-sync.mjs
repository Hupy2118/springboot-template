#!/usr/bin/env node
import { cp, mkdir, readFile, readdir, rm, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ASSEMBLY_MANAGED_FILES, GENERATED_MARKER } from './assembly-contract.mjs';
import { extensionsForProfile, frontendRoot, profileConfig } from './profiles.mjs';
import { readWorkspaceState } from './workspace-state.mjs';

export class WorkspaceSyncError extends Error { constructor(code, message) { super(`${code}: ${message}`); this.code = code; } }

async function files(directory, prefix = '') {
  const entries = await readdir(directory, { withFileTypes: true });
  return (await Promise.all(entries.map(async (entry) => {
    const relative = path.join(prefix, entry.name).replaceAll('\\', '/');
    if (relative === GENERATED_MARKER || ASSEMBLY_MANAGED_FILES.has(relative)) return [];
    return entry.isDirectory() ? files(path.join(directory, entry.name), relative) : [relative];
  }))).flat().sort();
}

async function selectedExtensions(profile) {
  const selected = new Set(await extensionsForProfile(profile));
  const visit = async (id) => {
    const manifest = JSON.parse(await readFile(path.join(frontendRoot, 'extensions', id, 'extension.yaml'), 'utf8'));
    for (const required of manifest.requires || []) if (!selected.has(required)) { selected.add(required); await visit(required); }
  };
  for (const id of [...selected]) await visit(id);
  return [...selected].sort();
}

async function ensureWorkspace(profile, workspaceRoot) {
  const state = await readWorkspaceState();
  if (!state || state.profile !== profile) throw new WorkspaceSyncError('WORKSPACE_PROFILE_MISMATCH', `expected ${profile}`);
  const marker = JSON.parse(await readFile(path.join(workspaceRoot, GENERATED_MARKER), 'utf8'));
  if (marker.profile !== profile) throw new WorkspaceSyncError('WORKSPACE_PROFILE_MISMATCH', `workspace is ${marker.profile}`);
}

async function exists(file) { try { return (await stat(file)).isFile(); } catch { return false; } }
async function copy(source, target) { await mkdir(path.dirname(target), { recursive: true }); await cp(source, target); }
async function removeEmptyParents(directory, stop) { while (directory.startsWith(`${stop}${path.sep}`)) { try { if ((await readdir(directory)).length) return; await rm(directory, { recursive: true }); directory = path.dirname(directory); } catch { return; } } }

export async function syncWorkspace(profile, { workspaceRoot = path.join(frontendRoot, 'src'), sourceRoot, skipStateCheck = false } = {}) {
  if (!skipStateCheck) await ensureWorkspace(profile, workspaceRoot);
  const config = await profileConfig(profile);
  const extensions = await selectedExtensions(profile);
  const owners = [{ id: 'base', root: sourceRoot || path.join(frontendRoot, 'base', 'src') }, ...extensions.map((id) => ({ id, root: path.join(frontendRoot, 'extensions', id, 'src') }))];
  const workspaceFiles = await files(workspaceRoot);
  const plan = [];
  for (const relative of workspaceFiles) {
    const matching = [];
    for (const owner of owners) if (await exists(path.join(owner.root, relative))) matching.push(owner);
    if (matching.length > 1) throw new WorkspaceSyncError('SOURCE_OWNER_CONFLICT', relative);
    const owner = matching[0] || owners.find((item) => item.id === config.writeOwner);
    if (!owner) throw new WorkspaceSyncError('NEW_FILE_OWNER_REQUIRED', relative);
    plan.push({ type: matching.length ? 'UPDATE' : 'CREATE', relative, owner });
  }
  for (const owner of owners) for (const relative of await files(owner.root)) {
    if (!workspaceFiles.includes(relative)) plan.push({ type: 'DELETE', relative, owner });
  }
  const changes = [];
  for (const item of plan) {
    const target = path.join(item.owner.root, item.relative);
    if (item.type === 'DELETE') { await rm(target, { force: true }); await removeEmptyParents(path.dirname(target), item.owner.root); }
    else await copy(path.join(workspaceRoot, item.relative), target);
    changes.push(`${item.type} ${item.owner.id === 'base' ? 'base/src' : `extensions/${item.owner.id}/src`}/${item.relative}`);
  }
  return changes;
}

export async function syncBaseWorkspace(options = {}) { return syncWorkspace('base', options); }

async function main() {
  const argument = process.argv.find((item) => item.startsWith('--profile='));
  const profile = argument?.slice('--profile='.length) || 'base';
  for (const change of await syncWorkspace(profile)) process.stdout.write(`${change}\n`);
}
if (process.argv[1] === fileURLToPath(import.meta.url)) main().catch((error) => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
