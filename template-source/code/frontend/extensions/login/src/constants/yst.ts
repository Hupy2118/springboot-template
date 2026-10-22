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
            HREF: 'https://one-account-gateway.paasuat.cmbchina.cn/auth-server/auth',
            END_URL: `https://one-account-gateway.paasuat.cmbchina.cn/auth-server/endsession`,
            RESPONSE_TYPE: 'code',
            LOGOUT_SHARE_JS: 'https://one-account-gateway.paasuat.cmbchina.cn/logout-share/js/logoutShare.js',
            ENV: 'test'
          };

export default YST;
