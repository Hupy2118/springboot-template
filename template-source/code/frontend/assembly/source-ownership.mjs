import { readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { extensionsForProfile, frontendRoot } from './profiles.mjs';

export class SourceOwnershipError extends Error {
  constructor(code, message) { super(`${code}: ${message}`); this.code = code; }
}

async function exists(file) { try { return (await stat(file)).isFile(); } catch { return false; } }

async function requiredExtensions(profile) {
  const selected = new Set(await extensionsForProfile(profile));
  const visit = async (id) => {
    const manifest = JSON.parse(await readFile(path.join(frontendRoot, 'extensions', id, 'extension.yaml'), 'utf8'));
    for (const required of manifest.requires || []) if (!selected.has(required)) { selected.add(required); await visit(required); }
  };
  for (const id of [...selected]) await visit(id);
  return [...selected].sort();
}

export async function ownersForProfile(profile, { ownerRoots = {} } = {}) {
  const extensions = await requiredExtensions(profile);
  return [
    { id: 'base', root: ownerRoots.base || path.join(frontendRoot, 'base', 'src') },
    ...extensions.map((id) => ({ id, root: ownerRoots[id] || path.join(frontendRoot, 'extensions', id, 'src') })),
  ];
}

export async function resolveExistingOwner(relativePath, owners) {
  const normalized = relativePath.replaceAll('\\', '/');
  const matching = [];
  for (const owner of owners) if (await exists(path.join(owner.root, normalized))) matching.push(owner);
  if (matching.length > 1) throw new SourceOwnershipError('SOURCE_OWNER_CONFLICT', normalized);
  return matching[0];
}
