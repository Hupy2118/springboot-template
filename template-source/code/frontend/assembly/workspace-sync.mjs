#!/usr/bin/env node
import { cp, mkdir, readdir, readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ASSEMBLY_MANAGED_FILES, GENERATED_MARKER } from './assembly-contract.mjs';
import { frontendRoot, profileConfig } from './profiles.mjs';
import { ownersForProfile, resolveExistingOwner, SourceOwnershipError } from './source-ownership.mjs';
import { readWorkspaceState } from './workspace-state.mjs';

export class WorkspaceSyncError extends Error { constructor(code, message) { super(`${code}: ${message}`); this.code = code; } }

async function files(directory, prefix = '') {
  try {
    const entries = await readdir(directory, { withFileTypes: true });
    return (await Promise.all(entries.map(async (entry) => {
      const relative = path.join(prefix, entry.name).replaceAll('\\', '/');
      if (relative === GENERATED_MARKER || ASSEMBLY_MANAGED_FILES.has(relative)) return [];
      return entry.isDirectory() ? files(path.join(directory, entry.name), relative) : [relative];
    }))).flat().sort();
  } catch (error) {
    if (error.code === 'ENOENT') return [];
    throw error;
  }
}

async function ensureWorkspace(profile, workspaceRoot) {
  const state = await readWorkspaceState();
  if (!state || state.profile !== profile) throw new WorkspaceSyncError('WORKSPACE_PROFILE_MISMATCH', `expected ${profile}`);
  try {
    const marker = JSON.parse(await readFile(path.join(workspaceRoot, GENERATED_MARKER), 'utf8'));
    if (marker.profile !== profile) throw new WorkspaceSyncError('WORKSPACE_PROFILE_MISMATCH', `workspace is ${marker.profile}`);
  } catch (error) {
    if (error instanceof WorkspaceSyncError) throw error;
    throw new WorkspaceSyncError('WORKSPACE_PROFILE_MISMATCH', `expected ${profile}`);
  }
}

async function copy(source, target) { await mkdir(path.dirname(target), { recursive: true }); await cp(source, target); }
async function sameContent(left, right) { const [a, b] = await Promise.all([readFile(left), readFile(right)]); return a.equals(b); }
async function removeEmptyParents(directory, stop) {
  while (directory.startsWith(`${stop}${path.sep}`)) {
    try { if ((await readdir(directory)).length) return; await rm(directory); directory = path.dirname(directory); } catch { return; }
  }
}
function sourceLabel(owner, relative) { return `${owner.id === 'base' ? 'base/src' : `extensions/${owner.id}/src`}/${relative}`; }

export async function syncWorkspace(profile, { workspaceRoot = path.join(frontendRoot, 'src'), ownerRoots, skipStateCheck = false } = {}) {
  const config = await profileConfig(profile);
  if (config.editTarget === null) throw new WorkspaceSyncError('PROFILE_NOT_SYNCABLE', profile);
  if (!skipStateCheck) await ensureWorkspace(profile, workspaceRoot);
  const owners = await ownersForProfile(profile, { ownerRoots });
  const editTarget = owners.find((owner) => owner.id === config.editTarget);
  if (!editTarget) throw new WorkspaceSyncError('NEW_FILE_OWNER_REQUIRED', profile);
  const workspaceFiles = new Set(await files(workspaceRoot));
  const sourceFiles = new Set((await Promise.all(owners.map((owner) => files(owner.root)))).flat());
  const plan = [];
  for (const relative of [...new Set([...workspaceFiles, ...sourceFiles])].sort()) {
    let owner;
    try { owner = await resolveExistingOwner(relative, owners); }
    catch (error) {
      if (error instanceof SourceOwnershipError) throw new WorkspaceSyncError(error.code, relative);
      throw error;
    }
    if (workspaceFiles.has(relative)) {
      if (!owner) plan.push({ type: 'CREATE', relative, owner: editTarget });
      else if (!(await sameContent(path.join(workspaceRoot, relative), path.join(owner.root, relative)))) plan.push({ type: 'UPDATE', relative, owner });
    } else if (owner) plan.push({ type: 'DELETE', relative, owner });
  }
  const changes = [];
  for (const item of plan) {
    const target = path.join(item.owner.root, item.relative);
    if (item.type === 'DELETE') { await rm(target, { force: true }); await removeEmptyParents(path.dirname(target), item.owner.root); }
    else await copy(path.join(workspaceRoot, item.relative), target);
    changes.push(`${item.type} ${sourceLabel(item.owner, item.relative)}`);
  }
  return changes;
}

async function main() {
  const argument = process.argv.find((item) => item.startsWith('--profile='));
  if (!argument) throw new WorkspaceSyncError('PROFILE_REQUIRED', '--profile is required');
  for (const change of await syncWorkspace(argument.slice('--profile='.length))) process.stdout.write(`${change}\n`);
}
if (process.argv[1] === fileURLToPath(import.meta.url)) main().catch((error) => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
