import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { dependencyFingerprint, ensureDependencies, isDependencyRuntimeCurrent, writeDependencyRuntime } from './dependency-runtime.mjs';

const workspace = await mkdtemp(path.join(os.tmpdir(), 'template-dependency-runtime-'));

async function application(packageManager = 'pnpm@11.9.0') {
  await writeFile(path.join(workspace, 'package.json'), `${JSON.stringify({ name: 'test', private: true, packageManager })}\n`);
  await writeFile(path.join(workspace, 'pnpm-lock.yaml'), 'lockfileVersion: \'9.0\'\n');
}

try {
  await application();
  const initialFingerprint = await dependencyFingerprint(workspace);
  assert.equal(await isDependencyRuntimeCurrent(workspace), false);

  let installs = 0;
  const fakeInstall = async (root) => { installs += 1; await mkdir(path.join(root, 'node_modules')); };
  assert.deepEqual(await ensureDependencies(workspace, { installDependencies: fakeInstall }), { reused: false });
  assert.equal(installs, 1);
  assert.equal(await isDependencyRuntimeCurrent(workspace), true);
  assert.deepEqual(await ensureDependencies(workspace, { installDependencies: fakeInstall }), { reused: true });
  assert.equal(installs, 1);

  await application('pnpm@11.10.0');
  assert.notEqual(await dependencyFingerprint(workspace), initialFingerprint);
  assert.equal(await isDependencyRuntimeCurrent(workspace), false);
  await writeDependencyRuntime(workspace);
  assert.equal(await isDependencyRuntimeCurrent(workspace), true);
  process.stdout.write('Dependency runtime scenarios passed.\n');
} finally {
  await rm(workspace, { recursive: true, force: true });
}
