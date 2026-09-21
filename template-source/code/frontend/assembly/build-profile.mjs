import { execFile } from 'node:child_process';
import { cp, mkdtemp, rm, stat } from 'node:fs/promises';
import { promisify } from 'node:util';
import path from 'node:path';
import { extensionsForProfile, frontendRoot } from './profiles.mjs';
import { statePath } from './workspace-state.mjs';

const execute = promisify(execFile);
const profileArgument = process.argv.find((item) => item.startsWith('--profile='));
const profile = profileArgument?.slice('--profile='.length) || 'full';
await extensionsForProfile(profile);
const assembler = path.join(frontendRoot, 'assembly', 'assemble.mjs');
const workspace = path.join(frontendRoot, 'src');

async function exists(target) { try { await stat(target); return true; } catch (error) { if (error.code === 'ENOENT') return false; throw error; } }

const backup = await mkdtemp(path.join(frontendRoot, '.build-workspace-'));
const workspaceBackup = path.join(backup, 'src');
const stateBackup = path.join(backup, 'workspace-state.json');
const hadWorkspace = await exists(workspace);
const hadState = await exists(statePath);
try {
  if (hadWorkspace) await cp(workspace, workspaceBackup, { recursive: true });
  if (hadState) await cp(statePath, stateBackup);
  await execute(process.execPath, [assembler, `--profile=${profile}`, '--force=true'], { cwd: frontendRoot });
  await execute('pnpm', ['exec', 'tsc', '-b'], { cwd: frontendRoot });
  await execute('pnpm', ['exec', 'vite', 'build'], { cwd: frontendRoot });
} finally {
  await rm(workspace, { recursive: true, force: true });
  if (hadWorkspace) await cp(workspaceBackup, workspace, { recursive: true });
  await rm(statePath, { force: true });
  if (hadState) await cp(stateBackup, statePath);
  await rm(backup, { recursive: true, force: true });
}
