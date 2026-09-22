import { execFile } from 'node:child_process';
import { cp, mkdtemp, rename, rm, stat } from 'node:fs/promises';
import { promisify } from 'node:util';
import path from 'node:path';
import { extensionsForProfile, frontendRoot } from './profiles.mjs';
import { statePath } from './workspace-state.mjs';

const execute = promisify(execFile);
const profileArgument = process.argv.find((item) => item.startsWith('--profile='));
const profile = profileArgument?.slice('--profile='.length) || 'full';
await extensionsForProfile(profile);
const assembler = path.join(frontendRoot, 'assembly', 'assemble.mjs');
const workspace = path.join(frontendRoot, 'workspace');

async function exists(target) { try { await stat(target); return true; } catch (error) { if (error.code === 'ENOENT') return false; throw error; } }

const backup = await mkdtemp(path.join(frontendRoot, '.build-workspace-'));
const workspaceBackup = path.join(backup, 'workspace');
const stateBackup = path.join(backup, '.devagentstudio-template-workspace.json');
const nodeModulesBackup = path.join(backup, 'node_modules');
const hadWorkspace = await exists(workspace);
const hadState = await exists(statePath);
const hadNodeModules = await exists(path.join(workspace, 'node_modules'));
let nodeModulesPreserved = false;

function copyWorkspaceSource(source, destination) {
  return cp(source, destination, {
    recursive: true,
    filter: (candidate) => !['node_modules', 'dist'].includes(path.basename(candidate)),
  });
}

async function preserveNodeModules() {
  if (!hadNodeModules) return;
  try {
    await rename(path.join(workspace, 'node_modules'), nodeModulesBackup);
    nodeModulesPreserved = true;
  }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
}

try {
  if (hadWorkspace) await copyWorkspaceSource(workspace, workspaceBackup);
  if (hadState) await cp(statePath, stateBackup);
  await execute(process.execPath, [assembler, `--profile=${profile}`, '--force=true'], { cwd: frontendRoot });
  await execute('pnpm', ['exec', 'tsc', '-b'], { cwd: workspace });
  await execute('pnpm', ['exec', 'vite', 'build'], { cwd: workspace });
} finally {
  await preserveNodeModules();
  await rm(workspace, { recursive: true, force: true });
  if (hadWorkspace) await copyWorkspaceSource(workspaceBackup, workspace);
  if (nodeModulesPreserved) await rename(nodeModulesBackup, path.join(workspace, 'node_modules'));
  await rm(statePath, { force: true });
  if (hadState) await cp(stateBackup, statePath);
  await rm(backup, { recursive: true, force: true });
}
