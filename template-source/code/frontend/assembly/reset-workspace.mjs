import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
import { frontendRoot, profileConfig } from './profiles.mjs';

const execute = promisify(execFile);
const argument = process.argv.find((item) => item.startsWith('--profile='));
const profile = argument?.slice('--profile='.length) || 'full';
await profileConfig(profile);
await execute(process.execPath, [path.join(frontendRoot, 'assembly', 'assemble.mjs'), `--profile=${profile}`, '--force=true'], { cwd: frontendRoot });
process.stdout.write(`Reset workspace to ${profile}.\n`);
