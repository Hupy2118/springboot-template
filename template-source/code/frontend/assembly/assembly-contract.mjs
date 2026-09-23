export const ASSEMBLY_MANAGED_FILES = new Set([
  'src/extensions/providers.ts',
  'src/extensions/rootRoutes.tsx',
  'src/extensions/systemPageRoutes.ts',
  'src/extensions/initializers.ts',
  'src/extensions/errorReporters.ts',
]);

export const GENERATED_MARKER = '.devagentstudio-template-generated.json';

export function isAssemblyManaged(relativePath) {
  return ASSEMBLY_MANAGED_FILES.has(relativePath.replaceAll('\\', '/'));
}
