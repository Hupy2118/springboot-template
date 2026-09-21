import { spawn } from 'node:child_process';
import { readdir, rename, rm, watch } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { extensionsForProfile, frontendRoot } from './profiles.mjs';

const profileArgument = process.argv.find((item) => item.startsWith('--profile='));
const profile = profileArgument?.slice('--profile='.length) || 'full';
const extensions = await extensionsForProfile(profile);
const assembler = path.join(frontendRoot, 'assembly', 'assemble.mjs');
const destination = path.join(frontendRoot, 'src');
let pending = false;
let rebuilding = false;

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: frontendRoot, stdio: 'inherit' });
    child.on('error', reject);
    child.on('exit', (code) => code === 0 ? resolve() : reject(new Error(`${command} exited with ${code}`)));
  });
}

async function assembleSafely() {
  if (rebuilding) { pending = true; return; }
  rebuilding = true;
  const staging = path.join(frontendRoot, `.assembly-next-${process.pid}`);
  const backup = path.join(frontendRoot, `.assembly-previous-${process.pid}`);
  try {
    await rm(staging, { recursive: true, force: true });
    await run(process.execPath, [assembler, `--profile=${profile}`, `--extensions=${extensions.join(',')}`, `--output=${path.basename(staging)}`]);
    await rm(backup, { recursive: true, force: true });
    await rename(destination, backup);
    await rename(staging, destination);
    await rm(backup, { recursive: true, force: true });
    process.stdout.write(`Assembled ${profile} profile.\n`);
  } catch (error) {
    await rm(staging, { recursive: true, force: true });
    process.stderr.write(`${error.message}\nKeeping the last valid src.\n`);
  } finally {
    rebuilding = false;
    if (pending) { pending = false; void assembleSafely(); }
  }
}

async function watchDirectory(directory) {
  try {
    const watcher = watch(directory);
    (async () => {
      for await (const event of watcher) {
        if (event.filename) {
          const changed = path.join(directory, event.filename.toString());
          try { if ((await readdir(changed)).length >= 0) void watchDirectory(changed); } catch { /* file change */ }
        }
        void assembleSafely();
      }
    })();
    for (const entry of await readdir(directory, { withFileTypes: true })) if (entry.isDirectory()) void watchDirectory(path.join(directory, entry.name));
  } catch (error) {
    process.stderr.write(`Watch failed for ${directory}: ${error.message}\n`);
  }
}

await assembleSafely();
await Promise.all([
  watchDirectory(path.join(frontendRoot, 'base', 'src')),
  watchDirectory(path.join(frontendRoot, 'extensions')),
  watchDirectory(path.join(frontendRoot, 'assembly')),
]);
const vite = spawn('pnpm', ['exec', 'vite', '--port', '3000'], { cwd: frontendRoot, stdio: 'inherit' });
vite.on('exit', (code) => process.exitCode = code ?? 0);
