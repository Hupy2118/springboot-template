#!/usr/bin/env node
import { cp, mkdir, readdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { extensionsForProfile } from './profiles.mjs';
import { ASSEMBLY_MANAGED_FILES } from './assembly-contract.mjs';
import { assertWorkspaceClean, writeWorkspaceState } from './workspace-state.mjs';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourceRoot = path.join(frontendRoot, 'base');
const extensionRoot = path.join(frontendRoot, 'extensions');

class AssemblyError extends Error {
  constructor(code, message) {
    super(`${code}: ${message}`);
    this.code = code;
  }
}

function argument(name, fallback) {
  const value = process.argv.find((item) => item.startsWith(`${name}=`));
  return value ? value.slice(name.length + 1) : fallback;
}

function outputDirectory() {
  const candidate = path.resolve(frontendRoot, argument('--output', 'workspace'));
  if (candidate === frontendRoot || !candidate.startsWith(`${frontendRoot}${path.sep}`)) {
    throw new AssemblyError('INVALID_OUTPUT_DIRECTORY', 'output must be a directory below the frontend root');
  }
  return candidate;
}

async function readManifest(directory) {
  const manifestPath = path.join(directory, 'extension.yaml');
  let manifest;
  try {
    // JSON is valid YAML and keeps the V1 compiler dependency-free.
    manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  } catch (error) {
    throw new AssemblyError('INVALID_EXTENSION_DEFINITION', `${manifestPath}: ${error.message}`);
  }
  validateManifest(manifest, manifestPath);
  return { ...manifest, directory };
}

function validateManifest(manifest, manifestPath) {
  const types = ['providers', 'rootRoutes', 'pageRoutes', 'initializers', 'errorReporters'];
  if (!manifest || typeof manifest.id !== 'string' || !Array.isArray(manifest.requires) || !manifest.contributes) {
    throw new AssemblyError('INVALID_EXTENSION_DEFINITION', `${manifestPath} is missing id, requires, or contributes`);
  }
  for (const type of types) {
    if (!Array.isArray(manifest.contributes[type])) {
      throw new AssemblyError('INVALID_EXTENSION_DEFINITION', `${manifestPath} contributes.${type} must be an array`);
    }
  }
  for (const type of ['providers', 'rootRoutes', 'initializers', 'errorReporters']) {
    for (const contribution of manifest.contributes[type]) {
      if (!contribution.id || !contribution.source || !contribution.export || !contribution.source.startsWith('src/')) {
        throw new AssemblyError('INVALID_EXTENSION_DEFINITION', `${manifest.id}.${type} has an invalid contribution`);
      }
    }
  }
  for (const route of manifest.contributes.rootRoutes) {
    if (!route.path?.startsWith('/')) throw new AssemblyError('INVALID_EXTENSION_DEFINITION', `${manifest.id} root route must start with /`);
  }
  for (const route of manifest.contributes.pageRoutes) {
    if (!route.id || !route.label || !/^[a-z0-9]+(?:_[a-z0-9]+)*$/.test(route.path || '')) {
      throw new AssemblyError('INVALID_EXTENSION_DEFINITION', `${manifest.id} page route is invalid`);
    }
  }
}

async function manifests() {
  const directories = (await readdir(extensionRoot, { withFileTypes: true }))
    .filter((entry) => entry.isDirectory()).map((entry) => path.join(extensionRoot, entry.name));
  const all = await Promise.all(directories.map(readManifest));
  const ids = new Set();
  for (const manifest of all) {
    if (ids.has(manifest.id)) throw new AssemblyError('DUPLICATE_EXTENSION_ID', manifest.id);
    ids.add(manifest.id);
  }
  return all;
}

function selectExtensions(all, requested, disabled) {
  const byId = new Map(all.map((manifest) => [manifest.id, manifest]));
  const selected = new Set(requested);
  const visit = (id, chain = []) => {
    const manifest = byId.get(id);
    if (!manifest) throw new AssemblyError('MISSING_EXTENSION_DEPENDENCY', `${chain.at(-1) || 'configuration'} requires ${id}`);
    if (chain.includes(id)) throw new AssemblyError('CIRCULAR_EXTENSION_DEPENDENCY', [...chain, id].join(' -> '));
    for (const dependency of manifest.requires) {
      if (disabled.has(dependency)) {
        throw new AssemblyError('EXPLICIT_EXTENSION_CONFLICT', `${id} requires explicitly disabled ${dependency}`);
      }
      selected.add(dependency);
      visit(dependency, [...chain, id]);
    }
  };
  for (const id of [...selected]) visit(id);
  const ordered = [];
  const visited = new Set();
  const visitOrder = (id) => {
    if (visited.has(id)) return;
    visited.add(id);
    const manifest = byId.get(id);
    for (const dependency of manifest.requires) visitOrder(dependency);
    ordered.push(manifest);
  };
  [...selected].sort().forEach(visitOrder);
  return ordered;
}

async function filesRecursively(directory, prefix = '') {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.sort((a, b) => a.name.localeCompare(b.name)).map(async (entry) => {
    const relative = path.join(prefix, entry.name);
    return entry.isDirectory() ? filesRecursively(path.join(directory, entry.name), relative) : [relative];
  }));
  return nested.flat();
}

async function verifyContributionSources(extensions) {
  for (const extension of extensions) {
    for (const type of ['providers', 'rootRoutes', 'initializers', 'errorReporters']) {
      for (const contribution of extension.contributes[type]) {
        try {
          const sourcePath = path.join(extension.directory, contribution.source);
          if (!(await stat(sourcePath)).isFile()) throw new Error('not a file');
          const source = await readFile(sourcePath, 'utf8');
          const exportPattern = contribution.export === 'default'
            ? /export\s+default\b/
            : new RegExp(`export\\s+(?:async\\s+)?(?:const|let|var|function|class)\\s+${contribution.export}\\b|export\\s*\\{[^}]*\\b${contribution.export}\\b`);
          if (!exportPattern.test(source)) {
            throw new AssemblyError('INVALID_EXTENSION_DEFINITION', `${extension.id}: ${contribution.source} does not export ${contribution.export}`);
          }
        } catch (error) {
          if (error instanceof AssemblyError) throw error;
          throw new AssemblyError('MISSING_SOURCE', `${extension.id}: ${contribution.source}`);
        }
      }
    }
    for (const route of extension.contributes.pageRoutes) {
      const name = route.path.split('_').map((part) => part[0].toUpperCase() + part.slice(1)).join('');
      try {
        if (!(await stat(path.join(extension.directory, 'src/pages', name, 'index.tsx'))).isFile()) throw new Error('not a file');
      } catch {
        throw new AssemblyError('MISSING_SOURCE', `${extension.id}: src/pages/${name}/index.tsx`);
      }
    }
  }
}

function contributionList(extensions, key) {
  const contributions = extensions.flatMap((extension) => extension.contributes[key].map((item) => ({ ...item, extension })));
  const ids = new Set();
  for (const item of contributions) {
    if (ids.has(item.id)) throw new AssemblyError('DUPLICATE_CONTRIBUTION_ID', item.id);
    ids.add(item.id);
  }
  return contributions;
}

function imports(contributions, prefix) {
  return contributions.map((item, index) => {
    const relative = item.source.slice(4).replace(/\.(tsx?|jsx?)$/, '');
    const local = `${prefix}${index}`;
    return { local, line: item.export === 'default' ? `import ${local} from '@/${relative}';` : `import { ${item.export} as ${local} } from '@/${relative}';` };
  });
}

function generateProviderRegistry(providers) {
  const entries = imports(providers, 'Provider');
  return `import type { ComponentType, PropsWithChildren } from 'react';\n${entries.map((entry) => entry.line).join('\n')}\n\nexport const extensionProviders: ComponentType<PropsWithChildren>[] = [${entries.map((entry) => entry.local).join(', ')}];\n`;
}

function generateRootRouteRegistry(routes) {
  const paths = new Set();
  for (const route of routes) {
    if (paths.has(route.path)) throw new AssemblyError('DUPLICATE_ROUTE', route.path);
    paths.add(route.path);
  }
  const entries = imports(routes, 'RootRoute');
  return `import type { RouteObject } from 'react-router-dom';\n${entries.map((entry) => entry.line).join('\n')}\n\nexport const extensionRootRoutes: RouteObject[] = [${routes.map((route, index) => `\n  { path: '${route.path}', element: <${entries[index].local} /> },`).join('')}\n];\n`;
}

function generatePageRouteRegistry(routes) {
  const paths = new Set();
  for (const route of routes) {
    if (paths.has(route.path)) throw new AssemblyError('DUPLICATE_ROUTE', route.path);
    paths.add(route.path);
  }
  const body = routes.map((route) => `  { path: '${route.path}', label: ${JSON.stringify(route.label)}${route.icon ? `, icon: '${route.icon}'` : ''}${route.resourceKey ? `, resourceKey: '${route.resourceKey}'` : ''} },`).join('\n');
  return `import type { PageRouteDefinition } from '@/typings/routes';\n\nexport const extensionSystemPageRoutes: PageRouteDefinition[] = [\n${body}\n];\n`;
}

function generateInitializerRegistry(items) {
  const entries = imports(items, 'Initializer');
  return `${entries.map((entry) => entry.line).join('\n')}\n\nexport const extensionInitializers: Array<() => void | Promise<void>> = [${entries.map((entry) => entry.local).join(', ')}];\n`;
}

function generateErrorReporterRegistry(items) {
  const entries = imports(items, 'ErrorReporter');
  return `${entries.map((entry) => entry.line).join('\n')}\n\nexport const extensionErrorReporters: Array<(error: Error, info?: unknown) => void> = [${entries.map((entry) => entry.local).join(', ')}];\n`;
}

async function assemble() {
  const requestedValue = argument('--extensions', undefined);
  const profile = requestedValue === undefined ? argument('--profile', 'full') : undefined;
  const requestedFromProfile = profile ? await extensionsForProfile(profile) : undefined;
  const requested = requestedFromProfile || (requestedValue ? requestedValue.split(',').filter(Boolean) : []);
  const disabledValue = argument('--disabled', '');
  const disabled = new Set(disabledValue ? disabledValue.split(',').filter(Boolean) : []);
  if (requested.some((id) => disabled.has(id))) {
    throw new AssemblyError('EXPLICIT_EXTENSION_CONFLICT', 'an extension cannot be both enabled and disabled');
  }
  const selected = selectExtensions(await manifests(), requested, disabled);
  await verifyContributionSources(selected);
  const destination = outputDirectory();
  const primaryOutput = destination === path.join(frontendRoot, 'workspace');
  if (primaryOutput && argument('--force', '') !== 'true') await assertWorkspaceClean();
  const preservedNodeModules = path.join(frontendRoot, `.workspace-node_modules-${process.pid}`);
  if (primaryOutput) {
    await rm(preservedNodeModules, { recursive: true, force: true });
    try { await rename(path.join(destination, 'node_modules'), preservedNodeModules); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  await rm(destination, { recursive: true, force: true });
  await mkdir(destination, { recursive: true });
  await cp(sourceRoot, destination, { recursive: true });
  const owners = new Map((await filesRecursively(sourceRoot)).map((file) => [file, 'base']));
  for (const extension of selected) {
    const extensionSource = path.join(extension.directory, 'src');
    for (const file of await filesRecursively(extensionSource)) {
      const target = path.posix.join('src', file.replaceAll('\\', '/'));
      if (ASSEMBLY_MANAGED_FILES.has(target) || owners.has(target)) throw new AssemblyError('FILE_COLLISION', `${owners.get(target) || 'assembly'} and ${extension.id}: src/${file}`);
      owners.set(target, extension.id);
    }
    await cp(extensionSource, path.join(destination, 'src'), { recursive: true });
  }
  const providers = contributionList(selected, 'providers');
  const rootRouteContributions = contributionList(selected, 'rootRoutes');
  const pageRouteContributions = contributionList(selected, 'pageRoutes');
  const initializerContributions = contributionList(selected, 'initializers');
  const reporterContributions = contributionList(selected, 'errorReporters');
  const generated = new Map([
    ['src/generated/extensions/providers.ts', generateProviderRegistry(providers)],
    ['src/generated/extensions/rootRoutes.tsx', generateRootRouteRegistry(rootRouteContributions)],
    ['src/generated/extensions/systemPageRoutes.ts', generatePageRouteRegistry(pageRouteContributions)],
    ['src/generated/extensions/initializers.ts', generateInitializerRegistry(initializerContributions)],
    ['src/generated/extensions/errorReporters.ts', generateErrorReporterRegistry(reporterContributions)],
  ]);
  for (const [relative, content] of generated) {
    const target = path.join(destination, relative);
    await mkdir(path.dirname(target), { recursive: true });
    await writeFile(target, content);
  }
  await writeFile(path.join(destination, '.devagentstudio-template-generated.json'), `${JSON.stringify({
    generated: true,
    profile: profile || 'custom',
    extensions: selected.map((extension) => extension.id),
    assemblySchemaVersion: 1,
  }, null, 2)}\n`);
  if (primaryOutput) await writeFile(path.join(destination, '.gitkeep'), '');
  if (primaryOutput) {
    try { await rename(preservedNodeModules, path.join(destination, 'node_modules')); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  if (primaryOutput && profile) await writeWorkspaceState(profile);
  process.stdout.write(`Assembled ${selected.map((extension) => extension.id).join(', ') || 'base-only'} into ${path.relative(frontendRoot, destination)}\n`);
}

assemble().catch((error) => {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
});
