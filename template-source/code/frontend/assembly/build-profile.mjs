import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { extensionsForProfile, frontendRoot } from './profiles.mjs';

const execute = promisify(execFile);
const profileArgument = process.argv.find((item) => item.startsWith('--profile='));
const profile = profileArgument?.slice('--profile='.length) || 'full';
await extensionsForProfile(profile);
const assembler = path.join(frontendRoot, 'assembly', 'assemble.mjs');

try {
  await execute(process.execPath, [assembler, `--profile=${profile}`], { cwd: frontendRoot });
  await execute('pnpm', ['exec', 'tsc', '-b'], { cwd: frontendRoot });
  await execute('pnpm', ['exec', 'vite', 'build'], { cwd: frontendRoot });
} finally {
  await execute(process.execPath, [assembler, '--profile=full'], { cwd: frontendRoot });
}
