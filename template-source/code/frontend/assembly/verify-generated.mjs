import { execFile } from 'node:child_process';
import { mkdtemp, readdir, readFile, rm } from 'node:fs/promises';
import { promisify } from 'node:util';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ASSEMBLY_MANAGED_FILES } from './assembly-contract.mjs';
import { ownersForProfile, resolveExistingOwner } from './source-ownership.mjs';

const execute = promisify(execFile);
const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const generatedRoot = path.join(frontendRoot, 'src');
const releaseManifest = path.join(frontendRoot, 'base.yaml');
const temporaryRoot = await mkdtemp(path.join(frontendRoot, '.assembly-verify-'));
const ignored = new Set(['.devagentstudio-template-generated.json']);

async function files(directory, prefix = '') {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map((entry) => {
    const relative = path.join(prefix, entry.name);
    if (ignored.has(relative)) return [];
    return entry.isDirectory() ? files(path.join(directory, entry.name), relative) : [relative];
  }));
  return nested.flat().sort();
}

const owners = await ownersForProfile('full');
async function expectedSource(relative) {
  if (ASSEMBLY_MANAGED_FILES.has(relative)) return 'assembly';
  const owner = await resolveExistingOwner(relative, owners);
  return owner ? `${owner.id === 'base' ? 'base/src' : `extensions/${owner.id}/src`}/${relative}` : 'unknown';
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
    const labels = await Promise.all(drift.map(async (relative) => `src/${relative}\nexpected source: ${await expectedSource(relative)}`));
    process.stderr.write(`GENERATED_SOURCE_DRIFT\n${labels.join('\n')}`);
    process.exitCode = 1;
  } else {
    process.stdout.write('Generated source matches the full Assembly output.\n');
  }
  const manifest = await readFile(releaseManifest, 'utf8');
  const released = new Set([...manifest.matchAll(/source:\s*frontend\/src\/([^,\s}]+)/g)].map((match) => match[1]));
  const missingReleaseFiles = expected.filter((relative) => !released.has(relative));
  if (missingReleaseFiles.length) {
    process.stderr.write(`RELEASE_MANIFEST_GAP\n${missingReleaseFiles.map((relative) => `base.yaml is missing frontend/src/${relative}`).join('\n')}\n`);
    process.exitCode = 1;
  }
} finally {
  await rm(temporaryRoot, { recursive: true, force: true });
}
