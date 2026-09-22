export const ASSEMBLY_MANAGED_FILES = new Set([
  'generated/extensions/providers.ts',
  'generated/extensions/rootRoutes.tsx',
  'generated/extensions/systemPageRoutes.ts',
  'generated/extensions/initializers.ts',
  'generated/extensions/errorReporters.ts',
]);

export const GENERATED_MARKER = '.devagentstudio-template-generated.json';

export function isAssemblyManaged(relativePath) {
  return ASSEMBLY_MANAGED_FILES.has(relativePath.replaceAll('\\', '/'));
}
