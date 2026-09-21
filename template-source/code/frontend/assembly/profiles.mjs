import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export async function extensionsForProfile(profile) {
  const profiles = JSON.parse(await readFile(path.join(frontendRoot, 'assembly', 'profiles.json'), 'utf8'));
  if (!Object.prototype.hasOwnProperty.call(profiles, profile) || !Array.isArray(profiles[profile])) {
    throw new Error(`UNKNOWN_PROFILE: ${profile}`);
  }
  return profiles[profile];
}
