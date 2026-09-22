#!/usr/bin/env node
import { mkdtemp, rm, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { GENERATED_MARKER } from './assembly-contract.mjs';

const execute = promisify(execFile);
const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const releaseRoot = await mkdtemp(path.join(frontendRoot, '.assembly-release-'));
const maintenanceOnly = ['base', 'extensions', 'assembly', 'workspace', 'TEMPLATE_DEVELOPMENT.md', 'base.md', 'extension.md', '.gitkeep'];

async function exists(target) {
  try { await stat(target); return true; } catch (error) { if (error.code === 'ENOENT') return false; throw error; }
}

try {
  await execute(process.execPath, [path.join(frontendRoot, 'assembly', 'assemble.mjs'), '--profile=full', `--output=${path.relative(frontendRoot, releaseRoot)}`], { cwd: frontendRoot });
  await rm(path.join(releaseRoot, GENERATED_MARKER), { force: true });
  await rm(path.join(releaseRoot, '.devagentstudio-template-workspace.json'), { force: true });
  await rm(path.join(releaseRoot, '.gitkeep'), { force: true });
  const leaked = [];
  for (const entry of maintenanceOnly) if (await exists(path.join(releaseRoot, entry))) leaked.push(entry);
  if (leaked.length) {
    process.stderr.write(`RELEASE_MAINTENANCE_LEAK\n${leaked.join('\n')}\n`);
    process.exitCode = 1;
  } else {
    process.stdout.write('Release workspace contains only application files.\n');
  }
} finally {
  await rm(releaseRoot, { recursive: true, force: true });
}
