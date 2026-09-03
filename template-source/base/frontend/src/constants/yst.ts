import { YstConfigType } from '@/typings/yst';

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
    : process.env.WORKS_ENV === 'prd'
      ? {
          CLIENT_ID: '143fe77002c1444485dcd95808fdc5a7',
          REDIRECT_URI: `${window.location.origin}/login`,
          LOGOUT_URI: `${window.location.origin}/logout`,
          HREF: 'https://oa-auth.paas.cmbchina.com/auth-server/auth',
          END_URL: `https://oa-auth.paas.cmbchina.com/auth-server/endsession`,
          RESPONSE_TYPE: 'code',
          LOGOUT_SHARE_JS: 'https://oa-auth.paas.cmbchina.com/logout-share/js/logoutShare.js',
          ENV: 'prod'
        }
      : process.env.WORKS_ENV === 'biz'
        ? {
            CLIENT_ID: '143fe77002c1444485dcd95808fdc5a7',
            REDIRECT_URI: `${window.location.origin}/login`,
            LOGOUT_URI: `${window.location.origin}/logout`,
            HREF: 'https://oa-auth.paas.cmbchina.cn/auth-server/auth',
            END_URL: `https://oa-auth.paas.cmbchina.cn/auth-server/endsession`,
            RESPONSE_TYPE: 'code',
            LOGOUT_SHARE_JS: 'https://oa-auth.paas.cmbchina.cn/logout-share/js/logoutShare.js',
            ENV: 'biz'
          }
        : {
            CLIENT_ID: '143fe77002c1444485dcd95808fdc5a7',
            REDIRECT_URI: `${window.location.origin}/login`,
            LOGOUT_URI: `${window.location.origin}/logout`,
            HREF: 'https://one-account-gateway.paasuat.cmbchina.cn/auth-server/auth',
            END_URL: `https://one-account-gateway.paasuat.cmbchina.cn/auth-server/endsession`,
            RESPONSE_TYPE: 'code',
            LOGOUT_SHARE_JS: 'https://one-account-gateway.paasuat.cmbchina.cn/logout-share/js/logoutShare.js',
            ENV: 'test'
          };

export default YST;

