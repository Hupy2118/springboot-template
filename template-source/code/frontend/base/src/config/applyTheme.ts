import { appConfig } from './appConfig';

/** Applies the build-time application theme before React renders. */
export function applyTheme() {
  document.documentElement.style.setProperty(
    '--app-primary-color',
    appConfig.theme.primaryColor,
  );
}
