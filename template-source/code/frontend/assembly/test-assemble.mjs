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
  let providers = await readFile(path.join(output, 'providers', 'AppProviders.tsx'), 'utf8');
  let rootRoutes = await readFile(path.join(output, 'routes', 'rootRoutes.tsx'), 'utf8');
  let pageRoutes = await readFile(path.join(output, 'routes', 'systemPageRoutes.ts'), 'utf8');
  assert.match(providers, /<IdentityProvider>\{children\}<\/IdentityProvider>/);
  assert.match(rootRoutes, /rootRoutes: RouteObject\[\] = \[\n\];/);
  assert.match(pageRoutes, /SYSTEM_PAGE_ROUTES: PageRouteDefinition\[\] = \[\n\s*\];/);

  await assemble('login');
  providers = await readFile(path.join(output, 'providers', 'AppProviders.tsx'), 'utf8');
  rootRoutes = await readFile(path.join(output, 'routes', 'rootRoutes.tsx'), 'utf8');
  assert.match(providers, /<IdentityProvider><Provider0>\{children\}<\/Provider0><\/IdentityProvider>/);
  assert.match(rootRoutes, /path: '\/login'/);
  assert.match(rootRoutes, /path: '\/logout'/);

  await assemble('login,authorization');
  providers = await readFile(path.join(output, 'providers', 'AppProviders.tsx'), 'utf8');
  pageRoutes = await readFile(path.join(output, 'routes', 'systemPageRoutes.ts'), 'utf8');
  assert.match(providers, /<IdentityProvider><Provider0><Provider1>\{children\}<\/Provider1><\/Provider0><\/IdentityProvider>/);
  assert.match(pageRoutes, /path: 'authorization_management'/);

  await assert.rejects(
    execute(process.execPath, [assembler, '--extensions=authorization', '--disabled=login'], { cwd: frontendRoot }),
    /EXPLICIT_EXTENSION_CONFLICT/,
  );
  process.stdout.write('Assembly scenarios passed.\n');
} finally {
  await rm(output, { recursive: true, force: true });
}
