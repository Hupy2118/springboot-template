import { useCallback, useEffect, useMemo } from 'react';
import qs from 'qs';
import { CURRENT_URL, USER_INFO_KEY } from '@/login/constants';
import { YST } from '@/constants/yst';
import { AuthnSourceEnum, IAuthInfo, IUserInfo } from '@/login/types';

type useGuardProps = {
  userInfo: IUserInfo | null;
  setAuthInfo: React.Dispatch<React.SetStateAction<IAuthInfo | null>>;
}

export default function useGuard({ userInfo, setAuthInfo }: useGuardProps) {
  const userInfoFromSessionStr = sessionStorage.getItem(USER_INFO_KEY);
  const isLoginPage = useMemo(() => {
    return window.location.pathname === '/login'
  }, []);

  
    const handleLogin = useCallback(() => {
    // 保存当前url到localStorage中
    const currentUrl = window.location.pathname + window.location.search;
    window.localStorage.setItem(CURRENT_URL, currentUrl);
    // 跳转到YHT登录地址
    window.location.href = `${YST.HREF}?${qs.stringify({
      client_id: YST.CLIENT_ID,
      redirect_uri: YST.REDIRECT_URI,
      response_type: YST.RESPONSE_TYPE,
    })}`;
  }, [YST]);

  const listenYHTLogout = useCallback(() => {
    const src = YST.LOGOUT_SHARE_JS;

    const scriptDom = document.createElement('script');
    scriptDom.src = src;
    document.body.append(scriptDom);
    const logoutCallBack = () => {
      // 跳转登出的url走登出的逻辑
      window.location.href = YST.LOGOUT_URI;
    }

    scriptDom.onload = () => {
      const initLogoutInstance = (window as any).initLogoutInstance;
      initLogoutInstance(
        YST.ENV,// 统一登出APIJS环境、同时对应统一认证的环境（注意：测试环境请传入'test'）
        YST.CLIENT_ID,// 应用在一事通注册的应用id
        logoutCallBack // 类型为一个回调函数，用户需要在函数内填充业务系统退出登录的逻辑(注意:专业系统需要在此清除相关用户信息并自行跳转到登录页或刷新页面)
      );
    };
  }, [YST]);
  
  useEffect(() => {
    try {
      const userInfoFromSession = userInfoFromSessionStr ? JSON.parse(userInfoFromSessionStr) : null;
                  // 若没有用户信息，保存现在的地址，并跳转到登录页
      if (!import.meta.env.DEV && YST?.CLIENT_ID && !isLoginPage && !userInfo && !userInfoFromSession) {
        handleLogin();
      }
      setAuthInfo(() => ({
        loginUrl: YST.HREF,
        logoutUrl: YST.END_URL,
        loginRoute: YST.REDIRECT_URI,
        logoutRoute: YST.LOGOUT_URI,
        authnSource: AuthnSourceEnum.YHT,
      }));
      listenYHTLogout();
          } catch (error) {
      console.error(error)
    }
      }, [userInfo, setAuthInfo, userInfoFromSessionStr, YST, handleLogin, isLoginPage]);
  }
