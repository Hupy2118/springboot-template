import { spawn } from 'node:child_process';
import { rename, rm, stat } from 'node:fs/promises';
import path from 'node:path';
import { profileConfig, frontendRoot } from './profiles.mjs';
import { assertWorkspaceClean, writeWorkspaceState } from './workspace-state.mjs';
import { ensureDependencies, isDependencyRuntimeCurrent } from './dependency-runtime.mjs';

const profileArgument = process.argv.find((item) => item.startsWith('--profile='));
const profile = profileArgument?.slice('--profile='.length) || 'full';
await profileConfig(profile);
const assembler = path.join(frontendRoot, 'assembly', 'assemble.mjs');
const destination = path.join(frontendRoot, 'workspace');
let rebuilding = false;

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: frontendRoot, stdio: 'inherit' });
    child.on('error', reject);
    child.on('exit', (code) => code === 0 ? resolve() : reject(new Error(`${command} exited with ${code}`)));
  });
}

async function exists(target) {
  try { await stat(target); return true; }
  catch (error) { if (error.code === 'ENOENT') return false; throw error; }
}

async function assembleSafely() {
  if (rebuilding) return;
  rebuilding = true;
  const staging = path.join(frontendRoot, `.assembly-next-${process.pid}`);
  const backup = path.join(frontendRoot, `.assembly-previous-${process.pid}`);
  let migratedNodeModules = false;
  let previousWorkspaceMoved = false;
  try {
    await assertWorkspaceClean();
    await rm(staging, { recursive: true, force: true });
    await run(process.execPath, [assembler, `--profile=${profile}`, `--output=${path.basename(staging)}`]);
    const oldNodeModules = path.join(destination, 'node_modules');
    if (await isDependencyRuntimeCurrent(staging, oldNodeModules)) {
      await rename(oldNodeModules, path.join(staging, 'node_modules'));
      migratedNodeModules = true;
    }
    const dependencies = await ensureDependencies(staging);
    process.stdout.write(dependencies.reused ? 'Reusing verified workspace dependencies.\n' : 'Installed workspace dependencies.\n');
    await rm(backup, { recursive: true, force: true });
    if (await exists(destination)) {
      await rename(destination, backup);
      previousWorkspaceMoved = true;
    }
    await rename(staging, destination);
    await rm(backup, { recursive: true, force: true });
    await writeWorkspaceState(profile);
    process.stdout.write(`Assembled ${profile} profile.\n`);
    return true;
  } catch (error) {
    if (migratedNodeModules && !(await exists(path.join(destination, 'node_modules'))) && await exists(path.join(staging, 'node_modules'))) {
      await rename(path.join(staging, 'node_modules'), path.join(destination, 'node_modules'));
    }
    await rm(staging, { recursive: true, force: true });
    if (previousWorkspaceMoved && !(await exists(destination)) && await exists(backup)) await rename(backup, destination);
    process.stderr.write(`${error.message}\nKeeping the last valid workspace.\n`);
    return false;
  } finally {
    rebuilding = false;
  }
}

if (!(await assembleSafely())) process.exit(1);

if (profile === 'base') process.stdout.write('Workspace is editable. Run pnpm sync:base to persist changes to base/.\n');
else if (profile === 'full') process.stdout.write('Full workspace is for integration verification. Do not use it as a source editing workspace.\n');
else process.stdout.write(`Workspace is editable. Run pnpm sync:${profile} to persist changes.\n`);

const vite = spawn('pnpm', ['exec', 'vite', '--port', '3000'], { cwd: destination, stdio: 'inherit' });
vite.on('exit', (code) => process.exitCode = code ?? 0);
