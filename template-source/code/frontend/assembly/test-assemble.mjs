import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { promisify } from 'node:util';
import path from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const execute = promisify(execFile);
const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const assembler = path.join(frontendRoot, 'assembly', 'assemble.mjs');
const output = await mkdtemp(path.join(frontendRoot, '.assembly-test-'));

async function assemble(extensions) {
  await execute(process.execPath, [assembler, `--extensions=${extensions}`, `--output=${path.relative(frontendRoot, output)}`], { cwd: frontendRoot });
}

try {
  await assemble('');
  assert.equal(await readFile(path.join(output, 'package.json'), 'utf8').then(() => true), true);
  assert.equal(await readFile(path.join(output, 'index.html'), 'utf8').then(() => true), true);
  assert.equal(await readFile(path.join(output, 'vite.config.ts'), 'utf8').then(() => true), true);
  assert.equal(await readFile(path.join(output, 'public', 'favicon.svg'), 'utf8').then(() => true), true);
  let providers = await readFile(path.join(output, 'src', 'generated', 'extensions', 'providers.ts'), 'utf8');
  let rootRoutes = await readFile(path.join(output, 'src', 'generated', 'extensions', 'rootRoutes.tsx'), 'utf8');
  let pageRoutes = await readFile(path.join(output, 'src', 'generated', 'extensions', 'systemPageRoutes.ts'), 'utf8');
  assert.match(providers, /extensionProviders: ComponentType<PropsWithChildren>\[\] = \[\];/);
  assert.match(rootRoutes, /extensionRootRoutes: RouteObject\[\] = \[\n\];/);
  assert.match(pageRoutes, /extensionSystemPageRoutes: PageRouteDefinition\[\] = \[\n\s*\];/);
  assert.match(await readFile(path.join(output, 'src', 'providers', 'AppProviders.tsx'), 'utf8'), /extensionProviders/);

  await assemble('login');
  providers = await readFile(path.join(output, 'src', 'generated', 'extensions', 'providers.ts'), 'utf8');
  rootRoutes = await readFile(path.join(output, 'src', 'generated', 'extensions', 'rootRoutes.tsx'), 'utf8');
  assert.match(providers, /extensionProviders: ComponentType<PropsWithChildren>\[\] = \[Provider0\];/);
  assert.match(rootRoutes, /path: '\/login'/);
  assert.match(rootRoutes, /path: '\/logout'/);

  await assemble('login,authorization');
  providers = await readFile(path.join(output, 'src', 'generated', 'extensions', 'providers.ts'), 'utf8');
  pageRoutes = await readFile(path.join(output, 'src', 'generated', 'extensions', 'systemPageRoutes.ts'), 'utf8');
  assert.match(providers, /extensionProviders: ComponentType<PropsWithChildren>\[\] = \[Provider0, Provider1\];/);
  assert.match(pageRoutes, /path: 'authorization_management'/);

  await assert.rejects(
    execute(process.execPath, [assembler, '--extensions=authorization', '--disabled=login'], { cwd: frontendRoot }),
    /EXPLICIT_EXTENSION_CONFLICT/,
  );
  process.stdout.write('Assembly scenarios passed.\n');
} finally {
  await rm(output, { recursive: true, force: true });
}
