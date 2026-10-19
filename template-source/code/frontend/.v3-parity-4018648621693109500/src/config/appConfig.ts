import type { AppConfig } from './types';

export const appConfig: AppConfig = {
  app: {
    name: import.meta.env.PUBLIC_APP_NAME || '示例应用',
    icon: import.meta.env.PUBLIC_APP_ICON || 'AppstoreOutlined',
  },
  theme: {
    primaryColor: import.meta.env.PUBLIC_THEME_PRIMARY_COLOR || '#6b3cf0',
  },
};
