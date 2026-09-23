import { extensionErrorReporters } from '@/extensions/errorReporters';

export function reportError(error: Error, info?: unknown) {
  for (const report of extensionErrorReporters) {
    try {
      report(error, info);
    } catch {
      // A reporter must not prevent the application from handling an error.
    }
  }
}
