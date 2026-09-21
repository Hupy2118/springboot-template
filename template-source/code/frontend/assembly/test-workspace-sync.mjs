import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { syncWorkspace } from './workspace-sync.mjs';

const root = await mkdtemp(path.join(tmpdir(), 'template-workspace-sync-'));
const workspace = path.join(root, 'src');
const owners = {
  base: path.join(root, 'base', 'src'),
  login: path.join(root, 'extensions', 'login', 'src'),
  authorization: path.join(root, 'extensions', 'authorization', 'src'),
};
async function write(rootDirectory, relative, content) { const target = path.join(rootDirectory, relative); await mkdir(path.dirname(target), { recursive: true }); await writeFile(target, content); }
async function reset() { await rm(root, { recursive: true, force: true }); await mkdir(root, { recursive: true }); }

try {
  // Base uses the same unified sync path: existing, new, deleted, and host files all belong to Base.
  await write(owners.base, 'layout/index.tsx', 'old base');
  await write(owners.base, 'providers/AppProviders.tsx', 'old host');
  await write(owners.base, 'utils/obsolete.ts', 'delete me');
  await write(workspace, 'layout/index.tsx', 'new base');
  await write(workspace, 'providers/AppProviders.tsx', 'new host');
  await write(workspace, 'components/New/index.tsx', 'new component');
  await write(workspace, 'generated/extensions/providers.ts', 'must be ignored');
  const baseChanges = await syncWorkspace('base', { workspaceRoot: workspace, ownerRoots: owners, skipStateCheck: true });
  assert.deepEqual(baseChanges, [
    'CREATE base/src/components/New/index.tsx',
    'UPDATE base/src/layout/index.tsx',
    'UPDATE base/src/providers/AppProviders.tsx',
    'DELETE base/src/utils/obsolete.ts',
  ]);
  assert.equal(await readFile(path.join(owners.base, 'providers/AppProviders.tsx'), 'utf8'), 'new host');
  await assert.rejects(readFile(path.join(owners.base, 'generated/extensions/providers.ts')));
  assert.equal((await syncWorkspace('base', { workspaceRoot: workspace, ownerRoots: owners, skipStateCheck: true })).length, 0);

  await reset();
  // Login: existing Base and Login files retain their owners; a new file uses login editTarget.
  await write(owners.base, 'layout/index.tsx', 'old base');
  await write(owners.base, 'utils/removed.ts', 'delete me');
  await write(owners.login, 'pages/Login/index.tsx', 'old login');
  await write(owners.login, 'utils/login-removed.ts', 'delete me');
  await write(workspace, 'layout/index.tsx', 'new base');
  await write(workspace, 'pages/Login/index.tsx', 'new login');
  await write(workspace, 'hooks/useLoginState.ts', 'new login hook');
  await write(workspace, 'generated/extensions/providers.ts', 'must be ignored');
  const loginChanges = await syncWorkspace('login', { workspaceRoot: workspace, ownerRoots: owners, skipStateCheck: true });
  assert.deepEqual(loginChanges, [
    'CREATE extensions/login/src/hooks/useLoginState.ts',
    'UPDATE base/src/layout/index.tsx',
    'UPDATE extensions/login/src/pages/Login/index.tsx',
    'DELETE extensions/login/src/utils/login-removed.ts',
    'DELETE base/src/utils/removed.ts',
  ]);
  assert.equal(await readFile(path.join(owners.base, 'layout/index.tsx'), 'utf8'), 'new base');
  assert.equal(await readFile(path.join(owners.login, 'hooks/useLoginState.ts'), 'utf8'), 'new login hook');
  await assert.rejects(readFile(path.join(owners.base, 'hooks/useLoginState.ts')));
  assert.equal((await syncWorkspace('login', { workspaceRoot: workspace, ownerRoots: owners, skipStateCheck: true })).length, 0);

  // An Authorization workspace contains its transitive Login dependency and writes new files to Authorization.
  await reset();
  await write(owners.base, 'base.ts', 'old');
  await write(owners.login, 'login.ts', 'old');
  await write(owners.authorization, 'authorization.ts', 'old');
  await write(workspace, 'base.ts', 'base update');
  await write(workspace, 'login.ts', 'login update');
  await write(workspace, 'authorization.ts', 'authorization update');
  await write(workspace, 'hooks/useRoleEditor.ts', 'new authorization hook');
  const authorizationChanges = await syncWorkspace('authorization', { workspaceRoot: workspace, ownerRoots: owners, skipStateCheck: true });
  assert.deepEqual(authorizationChanges, [
    'UPDATE extensions/authorization/src/authorization.ts',
    'UPDATE base/src/base.ts',
    'CREATE extensions/authorization/src/hooks/useRoleEditor.ts',
    'UPDATE extensions/login/src/login.ts',
  ]);

  // Duplicate paths are never resolved by priority, including when missing from the workspace.
  await reset();
  await write(owners.base, 'foo.ts', 'base');
  await write(owners.login, 'foo.ts', 'login');
  await assert.rejects(syncWorkspace('login', { workspaceRoot: workspace, ownerRoots: owners, skipStateCheck: true }), /SOURCE_OWNER_CONFLICT: foo.ts/);
  await assert.rejects(syncWorkspace('full', { workspaceRoot: workspace, ownerRoots: owners, skipStateCheck: true }), /PROFILE_NOT_SYNCABLE: full/);
  process.stdout.write('Base and Extension workspace sync scenarios passed.\n');
} finally {
  await rm(root, { recursive: true, force: true });
}
