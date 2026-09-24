import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

const RUNTIME_STATE_FILE = '.devagentstudio-template-dependency-runtime.json';

async function exists(target) {
  try { await stat(target); return true; }
  catch (error) { if (error.code === 'ENOENT') return false; throw error; }
}

export async function dependencyFingerprint(workspace) {
  const [packageJson, lockfile] = await Promise.all([
    readFile(path.join(workspace, 'package.json')),
    readFile(path.join(workspace, 'pnpm-lock.yaml')),
  ]);
  const packageManager = JSON.parse(packageJson).packageManager;
  if (typeof packageManager !== 'string' || !packageManager) {
    throw new Error(`DEPENDENCY_PACKAGE_MANAGER_MISSING: ${path.join(workspace, 'package.json')}`);
  }
  return createHash('sha256').update(JSON.stringify({
    packageJson: packageJson.toString('base64'),
    lockfile: lockfile.toString('base64'),
    node: process.version,
    platform: process.platform,
    arch: process.arch,
    os: `${os.type()} ${os.release()}`,
    packageManager,
  })).digest('hex');
}

function statePath(nodeModules) {
  return path.join(nodeModules, RUNTIME_STATE_FILE);
}

export async function isDependencyRuntimeCurrent(workspace, nodeModules = path.join(workspace, 'node_modules')) {
  if (!(await exists(nodeModules))) return false;
  try {
    const state = JSON.parse(await readFile(statePath(nodeModules), 'utf8'));
    return state.schemaVersion === 1 && state.fingerprint === await dependencyFingerprint(workspace);
  } catch (error) {
    if (error.code === 'ENOENT' || error instanceof SyntaxError) return false;
    throw error;
  }
}

export async function writeDependencyRuntime(workspace, nodeModules = path.join(workspace, 'node_modules')) {
  await mkdir(nodeModules, { recursive: true });
  await writeFile(statePath(nodeModules), `${JSON.stringify({ schemaVersion: 1, fingerprint: await dependencyFingerprint(workspace) }, null, 2)}\n`);
}

function install(workspace) {
  return new Promise((resolve, reject) => {
    const child = spawn('pnpm', ['install', '--frozen-lockfile'], { cwd: workspace, stdio: 'inherit' });
    child.on('error', reject);
    child.on('exit', (code) => code === 0 ? resolve() : reject(new Error(`pnpm install --frozen-lockfile exited with ${code}`)));
  });
}

export async function ensureDependencies(workspace, { installDependencies = install } = {}) {
  const nodeModules = path.join(workspace, 'node_modules');
  if (await isDependencyRuntimeCurrent(workspace, nodeModules)) return { reused: true };
  await rm(nodeModules, { recursive: true, force: true });
  await installDependencies(workspace);
  if (!(await exists(nodeModules))) throw new Error('DEPENDENCY_INSTALL_FAILED: pnpm did not create node_modules');
  await writeDependencyRuntime(workspace, nodeModules);
  return { reused: false };
}
