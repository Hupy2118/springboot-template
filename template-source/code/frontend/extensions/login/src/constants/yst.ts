import type { YstConfigType } from '@/typings/yst';

export const YST: YstConfigType =
  process.env.WORKS_ENV === 'local'
    ? {
        // 本地开发：关闭 SSO 认证
        CLIENT_ID: '',
        REDIRECT_URI: '',
        LOGOUT_URI: '',
        HREF: '',
        END_URL: '',
        RESPONSE_TYPE: 'code',
        LOGOUT_SHARE_JS: '',
        ENV: 'local'
      }
    : {
            CLIENT_ID: import.meta.env.PUBLIC_YHT_CLIENT_ID || '',
            REDIRECT_URI: `${window.location.origin}/login`,
            LOGOUT_URI: `${window.location.origin}/logout`,
            HREF: import.meta.env.PUBLIC_YHT_AUTHORIZE_URI || '',
            END_URL: import.meta.env.PUBLIC_YHT_END_URL || '',
            RESPONSE_TYPE: 'code',
            LOGOUT_SHARE_JS: import.meta.env.PUBLIC_YHT_LOGOUT_SHARE_JS || '',
            ENV: 'test'
          };

export default YST;
