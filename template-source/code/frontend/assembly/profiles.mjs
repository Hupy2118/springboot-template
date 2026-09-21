import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export async function profileConfig(profile) {
  const profiles = JSON.parse(await readFile(path.join(frontendRoot, 'assembly', 'profiles.json'), 'utf8'));
  const config = profiles[profile];
  if (!config || !Array.isArray(config.extensions) || !Object.prototype.hasOwnProperty.call(config, 'writeOwner')) {
    throw new Error(`UNKNOWN_PROFILE: ${profile}`);
  }
  return config;
}

export async function extensionsForProfile(profile) {
  return (await profileConfig(profile)).extensions;
}
