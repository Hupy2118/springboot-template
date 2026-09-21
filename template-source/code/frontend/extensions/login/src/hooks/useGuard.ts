import { useCallback, useEffect, useMemo } from 'react';
import qs from 'qs';
import { CURRENT_URL, USER_INFO_KEY } from '@/constants/login';
import { useIdentity } from '@/platform/identity/useIdentity';
import { YST } from '@/constants/yst';
import { useLogin } from '@/context/LoginContext';
import { AuthnSourceEnum, type LoginInfo } from '@/typings/login';

export function useGuard() {
  const { identity, setIdentity } = useIdentity();
  const { setAuthInfo } = useLogin();
  const storedIdentity = useMemo(() => {
    try {
      const stored = sessionStorage.getItem(USER_INFO_KEY);
      return stored ? JSON.parse(stored) : null;
    } catch {
      return null;
    }
  }, []);
  const handleLogin = useCallback(() => {
    window.localStorage.setItem(CURRENT_URL, window.location.pathname + window.location.search);
    window.location.href = `${YST.HREF}?${qs.stringify({
      client_id: YST.CLIENT_ID,
      redirect_uri: YST.REDIRECT_URI,
      response_type: YST.RESPONSE_TYPE,
    })}`;
  }, []);

  useEffect(() => {
    if (storedIdentity && !identity) setIdentity(storedIdentity);
    if (!import.meta.env.DEV && YST.CLIENT_ID && window.location.pathname !== '/login' && !identity && !storedIdentity) {
      handleLogin();
    }
    const authInfo: LoginInfo = {
      loginUrl: YST.HREF,
      logoutUrl: YST.END_URL,
      loginRoute: YST.REDIRECT_URI,
      logoutRoute: YST.LOGOUT_URI,
      authnSource: AuthnSourceEnum.YHT,
    };
    setAuthInfo(authInfo);
  }, [handleLogin, identity, setAuthInfo, setIdentity, storedIdentity]);
}
