import { extensionInitializers } from '@/generated/extensions/initializers';

export async function runAppInitializers() {
  for (const initialize of extensionInitializers) await initialize();
}
