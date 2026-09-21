import { execFile } from 'node:child_process';
import { mkdtemp, readdir, readFile, rm } from 'node:fs/promises';
import { promisify } from 'node:util';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const execute = promisify(execFile);
const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const generatedRoot = path.join(frontendRoot, 'src');
const temporaryRoot = await mkdtemp(path.join(frontendRoot, '.assembly-verify-'));
const ignored = new Set(['.xcodeagent-template-generated.json']);

async function files(directory, prefix = '') {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map((entry) => {
    const relative = path.join(prefix, entry.name);
    if (ignored.has(relative)) return [];
    return entry.isDirectory() ? files(path.join(directory, entry.name), relative) : [relative];
  }));
  return nested.flat().sort();
}

function expectedSource(relative) {
  for (const extension of ['login', 'authorization']) {
    // The precise file is confirmed by the generated comparison; this label identifies its authoritative tree.
    if (relative.startsWith('pages/Login/') || relative.startsWith('pages/Logout/') || relative === 'apis/login.ts' || relative.startsWith('context/LoginContext') || relative === 'hooks/useGuard.ts' || relative.startsWith('providers/LoginProvider') || relative === 'constants/login.ts' || relative === 'constants/yst.ts' || relative.startsWith('typings/login') || relative.startsWith('typings/yst')) return `extensions/login/src/${relative}`;
    if (relative === 'apis/authorization.ts' || relative.startsWith('components/Authorization/') || relative === 'constants/resources.ts' || relative === 'hooks/usePermission.ts' || relative.startsWith('pages/AuthorizationManagement/') || relative.startsWith('providers/AuthorizationProvider') || relative.startsWith('typings/authorization') || relative.startsWith('typings/generated/')) return `extensions/authorization/src/${relative}`;
  }
  return `base/src/${relative}`;
}

try {
  await execute(process.execPath, [path.join(frontendRoot, 'assembly', 'assemble.mjs'), '--profile=full', `--output=${path.relative(frontendRoot, temporaryRoot)}`], { cwd: frontendRoot });
  const [actual, expected] = await Promise.all([files(generatedRoot), files(temporaryRoot)]);
  const all = [...new Set([...actual, ...expected])].sort();
  const drift = [];
  for (const relative of all) {
    if (!actual.includes(relative) || !expected.includes(relative)) {
      drift.push(relative);
      continue;
    }
    const [left, right] = await Promise.all([readFile(path.join(generatedRoot, relative)), readFile(path.join(temporaryRoot, relative))]);
    if (!left.equals(right)) drift.push(relative);
  }
  if (drift.length) {
    process.stderr.write(`GENERATED_SOURCE_DRIFT\n${drift.map((relative) => `src/${relative}\nexpected source: ${expectedSource(relative)}`).join('\n')}`);
    process.exitCode = 1;
  } else {
    process.stdout.write('Generated source matches the full Assembly output.\n');
  }
} finally {
  await rm(temporaryRoot, { recursive: true, force: true });
}
