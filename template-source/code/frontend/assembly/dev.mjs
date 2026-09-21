import { spawn } from 'node:child_process';
import { rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { profileConfig, frontendRoot } from './profiles.mjs';
import { assertWorkspaceClean, writeWorkspaceState } from './workspace-state.mjs';

const profileArgument = process.argv.find((item) => item.startsWith('--profile='));
const profile = profileArgument?.slice('--profile='.length) || 'full';
await profileConfig(profile);
const assembler = path.join(frontendRoot, 'assembly', 'assemble.mjs');
const destination = path.join(frontendRoot, 'src');
let rebuilding = false;

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: frontendRoot, stdio: 'inherit' });
    child.on('error', reject);
    child.on('exit', (code) => code === 0 ? resolve() : reject(new Error(`${command} exited with ${code}`)));
  });
}

async function assembleSafely() {
  if (rebuilding) return;
  rebuilding = true;
  const staging = path.join(frontendRoot, `.assembly-next-${process.pid}`);
  const backup = path.join(frontendRoot, `.assembly-previous-${process.pid}`);
  try {
    await assertWorkspaceClean();
    await rm(staging, { recursive: true, force: true });
    await run(process.execPath, [assembler, `--profile=${profile}`, `--output=${path.basename(staging)}`]);
    await rm(backup, { recursive: true, force: true });
    await rename(destination, backup);
    await rename(staging, destination);
    await rm(backup, { recursive: true, force: true });
    await writeWorkspaceState(profile);
    process.stdout.write(`Assembled ${profile} profile.\n`);
  } catch (error) {
    await rm(staging, { recursive: true, force: true });
    process.stderr.write(`${error.message}\nKeeping the last valid src.\n`);
  } finally {
    rebuilding = false;
  }
}

await assembleSafely();

if (profile === 'base') process.stdout.write('Workspace is editable. Run pnpm sync:base to persist changes to base/src.\n');
else if (profile === 'full') process.stdout.write('Full workspace is for integration verification. Do not use it as a source editing workspace.\n');
else process.stdout.write(`Workspace is editable. Run pnpm sync:${profile} to persist changes.\n`);

const vite = spawn('pnpm', ['exec', 'vite', '--port', '3000'], { cwd: frontendRoot, stdio: 'inherit' });
vite.on('exit', (code) => process.exitCode = code ?? 0);
