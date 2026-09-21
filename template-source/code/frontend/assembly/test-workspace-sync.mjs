import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { syncBaseWorkspace } from './workspace-sync.mjs';

const root = await mkdtemp(path.join(tmpdir(), 'template-workspace-sync-'));
const workspaceRoot = path.join(root, 'src');
const sourceRoot = path.join(root, 'base', 'src');

async function write(relative, content, rootDirectory) {
  const target = path.join(rootDirectory, relative);
  await mkdir(path.dirname(target), { recursive: true });
  await writeFile(target, content);
}

try {
  await write('layout/index.tsx', 'workspace update\n', workspaceRoot);
  await write('components/New/index.tsx', 'workspace create\n', workspaceRoot);
  await write('providers/AppProviders.tsx', 'workspace update\n', workspaceRoot);
  await write('layout/index.tsx', 'source old\n', sourceRoot);
  await write('utils/obsolete.ts', 'source delete\n', sourceRoot);

  const changes = await syncBaseWorkspace({ workspaceRoot, sourceRoot, skipStateCheck: true });
  assert.deepEqual(changes, [
    'CREATE base/src/components/New/index.tsx',
    'UPDATE base/src/layout/index.tsx',
    'CREATE base/src/providers/AppProviders.tsx',
    'DELETE base/src/utils/obsolete.ts',
  ]);
  assert.equal(await readFile(path.join(sourceRoot, 'layout/index.tsx'), 'utf8'), 'workspace update\n');
  assert.equal(await readFile(path.join(sourceRoot, 'components/New/index.tsx'), 'utf8'), 'workspace create\n');
  await assert.rejects(readFile(path.join(sourceRoot, 'utils/obsolete.ts')));
  assert.equal(await readFile(path.join(sourceRoot, 'providers/AppProviders.tsx'), 'utf8'), 'workspace update\n');

  process.stdout.write('Base workspace mirror sync passed.\n');
} finally {
  await rm(root, { recursive: true, force: true });
}
