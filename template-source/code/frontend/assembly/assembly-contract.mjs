export const ASSEMBLY_MANAGED_FILES = new Set([
  'src/generated/extensions/providers.ts',
  'src/generated/extensions/rootRoutes.tsx',
  'src/generated/extensions/systemPageRoutes.ts',
  'src/generated/extensions/initializers.ts',
  'src/generated/extensions/errorReporters.ts',
]);

export const GENERATED_MARKER = '.devagentstudio-template-generated.json';

export function isAssemblyManaged(relativePath) {
  return ASSEMBLY_MANAGED_FILES.has(relativePath.replaceAll('\\', '/'));
}
