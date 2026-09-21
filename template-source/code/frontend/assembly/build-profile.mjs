import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { extensionsForProfile, frontendRoot } from './profiles.mjs';
import { assertWorkspaceClean, readWorkspaceState } from './workspace-state.mjs';

const execute = promisify(execFile);
const profileArgument = process.argv.find((item) => item.startsWith('--profile='));
const profile = profileArgument?.slice('--profile='.length) || 'full';
await extensionsForProfile(profile);
const assembler = path.join(frontendRoot, 'assembly', 'assemble.mjs');
const previous = await readWorkspaceState();

try {
  await assertWorkspaceClean();
  await execute(process.execPath, [assembler, `--profile=${profile}`], { cwd: frontendRoot });
  await execute('pnpm', ['exec', 'tsc', '-b'], { cwd: frontendRoot });
  await execute('pnpm', ['exec', 'vite', 'build'], { cwd: frontendRoot });
} finally {
  await execute(process.execPath, [assembler, `--profile=${previous?.profile || 'full'}`, '--force=true'], { cwd: frontendRoot });
}
