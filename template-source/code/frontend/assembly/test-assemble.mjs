import './test-agents-document.mjs';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
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
  for (const [extensions, ids] of [['', []], ['login', ['login']], ['authorization', ['login', 'authorization']], ['login,login,authorization', ['login', 'authorization']]]) {
    await assemble(extensions);
    const agents = await readFile(path.join(output, 'AGENTS.md'), 'utf8');
    assert.doesNotMatch(agents, /DEVAGENTSTUDIO:CAPABILITY|FRONTEND_PROJECT_TREE|AGENTS.fragment.md|extension.yaml|Base|Capability|Manifest|Assembly/);
    assert.equal(agents.includes('## 认证与登录'), ids.includes('login'));
    assert.equal(agents.includes('## 权限与访问控制'), ids.includes('authorization'));
    const trees = [...agents.matchAll(/```text\nfrontend\/\n([\s\S]*?)```/g)];
    assert.equal(trees.length, 1);
    const parents = [], shown = new Set();
    for (const line of trees[0][1].trimEnd().split('\n')) {
      const match = line.match(/^((?:│   |    )*)(?:├── |└── )(.+)$/);
      assert.ok(match, line);
      const depth = match[1].length / 4;
      parents.length = depth;
      const name = match[2].replace(/\/$/, '');
      const relative = [...parents, name].join('/');
      assert.ok(!shown.has(relative), relative);
      shown.add(relative);
      const info = await stat(path.join(output, relative));
      assert.equal(info.isDirectory(), match[2].endsWith('/'), relative);
      parents.push(name);
    }
    for (const registry of ['providers.ts', 'rootRoutes.tsx', 'systemPageRoutes.ts', 'initializers.ts', 'errorReporters.ts']) assert.ok(shown.has(`src/extensions/${registry}`));
    await assemble(extensions);
    assert.equal(agents, await readFile(path.join(output, 'AGENTS.md'), 'utf8'));
    assert.doesNotMatch(agents, /codebase-manifest\.json|docs\/project-structure\.md|docs\/capabilities\/|vite\.config\.ts|\buseRequest\b|ProTable/);
    assert.match(agents, /本表说明工程所有权，不授予任何文件读写权限/);
    assert.match(agents, /不构成对代码生成 Agent 的 Shell 执行授权/);
    assert.match(agents, /returnCode === 'SUC0000'/);
    assert.match(agents, /body，不要二次拆包/);
    if (ids.length === 0) {
      assert.doesNotMatch(agents, /LoginProvider|LoginContext|Permission\.tsx|usePermission|authorization\.ts|AUTHORIZATION_MANAGEMENT/);
      for (const title of ['工程概览与技术栈', '工程目录与文件职责', '核心架构与使用入口', '文件职责与受管边界']) assert.ok(agents.includes(title));
    }
    if (!ids.includes('authorization')) assert.doesNotMatch(agents, /AuthorizationProvider|Permission\.tsx|usePermission|resources\.ts|权限与访问控制/);
    if (ids.includes('login')) {
      assert.match(agents, /src\/context\/LoginContext\.tsx/);
      assert.match(agents, /encodeURIComponent/);
    }
    if (ids.includes('authorization')) {
      assert.match(agents, /getMyResources/);
      assert.match(agents, /RESOURCES\.SYSTEM\.AUTHORIZATION_MANAGEMENT/);
      assert.match(agents, /不是通用业务按钮资源键/);
    }
    // Every concrete inline source path must locate an existing generated entry.
    for (const [, relative] of agents.matchAll(/`((?:src|devtools)\/[A-Za-z0-9_./-]+)`/g)) {
      await readFile(path.join(output, relative)).catch(async (error) => {
        if (error.code !== 'EISDIR') throw error;
      });
    }
    await assert.rejects(readFile(path.join(output, 'AGENTS.fragment.md')));
    await assert.rejects(readFile(path.join(output, 'README.md')));
  }
  await assemble('');
  assert.equal(await readFile(path.join(output, 'package.json'), 'utf8').then(() => true), true);
  assert.equal(await readFile(path.join(output, 'index.html'), 'utf8').then(() => true), true);
  assert.equal(await readFile(path.join(output, 'rsbuild.config.ts'), 'utf8').then(() => true), true);
  assert.equal(await readFile(path.join(output, 'public', 'favicon.svg'), 'utf8').then(() => true), true);
  let providers = await readFile(path.join(output, 'src', 'extensions', 'providers.ts'), 'utf8');
  let rootRoutes = await readFile(path.join(output, 'src', 'extensions', 'rootRoutes.tsx'), 'utf8');
  let pageRoutes = await readFile(path.join(output, 'src', 'extensions', 'systemPageRoutes.ts'), 'utf8');
  assert.match(providers, /extensionProviders: ComponentType<PropsWithChildren>\[\] = \[\];/);
  assert.match(rootRoutes, /extensionRootRoutes: RouteObject\[\] = \[\n\];/);
  assert.match(pageRoutes, /extensionSystemPageRoutes: PageRouteDefinition\[\] = \[\n\s*\];/);
  assert.match(await readFile(path.join(output, 'src', 'providers', 'AppProviders.tsx'), 'utf8'), /extensionProviders/);

  await assemble('login');
  providers = await readFile(path.join(output, 'src', 'extensions', 'providers.ts'), 'utf8');
  rootRoutes = await readFile(path.join(output, 'src', 'extensions', 'rootRoutes.tsx'), 'utf8');
  assert.match(providers, /extensionProviders: ComponentType<PropsWithChildren>\[\] = \[Provider0\];/);
  assert.match(rootRoutes, /path: '\/login'/);
  assert.match(rootRoutes, /path: '\/logout'/);
  assert.match(rootRoutes, /import LoginPage from '@\/pages\/Login\/index';/);
  assert.match(rootRoutes, /import LogoutPage from '@\/pages\/Logout\/index';/);

  await assemble('login,authorization');
  providers = await readFile(path.join(output, 'src', 'extensions', 'providers.ts'), 'utf8');
  pageRoutes = await readFile(path.join(output, 'src', 'extensions', 'systemPageRoutes.ts'), 'utf8');
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
