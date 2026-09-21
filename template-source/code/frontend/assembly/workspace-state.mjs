import { execFile } from 'node:child_process';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { promisify } from 'node:util';
import { frontendRoot } from './profiles.mjs';
import { GENERATED_MARKER } from './assembly-contract.mjs';

const execute = promisify(execFile);
export const statePath = path.join(frontendRoot, '.xcodeagent-template-workspace.json');

async function files(root, prefix = '') {
  const entries = await readdir(root, { withFileTypes: true });
  const nested = await Promise.all(entries.map((entry) => {
    const relative = path.join(prefix, entry.name);
    if (relative === GENERATED_MARKER) return [];
    return entry.isDirectory() ? files(path.join(root, entry.name), relative) : [relative];
  }));
  return nested.flat().sort();
}

export async function readWorkspaceState() {
  try { return JSON.parse(await readFile(statePath, 'utf8')); } catch (error) { return error.code === 'ENOENT' ? undefined : Promise.reject(error); }
}

export async function writeWorkspaceState(profile) {
  await writeFile(statePath, `${JSON.stringify({ profile, schemaVersion: 1 }, null, 2)}\n`);
}

export async function workspaceDirty() {
  const state = await readWorkspaceState();
  if (!state) return { dirty: false };
  // assemble.mjs intentionally accepts output directories only below frontendRoot.
  // Keep this disposable comparison output within that boundary.
  const temporary = await mkdtemp(path.join(frontendRoot, '.workspace-status-'));
  try {
    await execute(process.execPath, [path.join(frontendRoot, 'assembly', 'assemble.mjs'), `--profile=${state.profile}`, `--output=${temporary}`], { cwd: frontendRoot });
    const workspace = path.join(frontendRoot, 'src');
    const [actual, expected] = await Promise.all([files(workspace), files(temporary)]);
    const all = [...new Set([...actual, ...expected])];
    const drift = [];
    for (const relative of all) {
      if (!actual.includes(relative) || !expected.includes(relative)) { drift.push(relative); continue; }
      const [left, right] = await Promise.all([readFile(path.join(workspace, relative)), readFile(path.join(temporary, relative))]);
      if (!left.equals(right)) drift.push(relative);
    }
    return { dirty: drift.length > 0, profile: state.profile, drift };
  } finally { await rm(temporary, { recursive: true, force: true }); }
}

export async function assertWorkspaceClean() {
  const status = await workspaceDirty();
  if (status.dirty) throw new Error(`WORKSPACE_SOURCE_DRIFT: ${status.drift.slice(0, 10).map((file) => `src/${file}`).join(', ')}`);
  return status;
}
