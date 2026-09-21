import { useCallback, useEffect } from 'react';
import qs from 'qs';
import { USER_INFO_KEY } from '@/constants';
import { useIdentity } from '@/platform/identity/useIdentity';
import { logoutApi } from '@/apis/login';
import { YST } from '@/constants/yst';
import { useLogin } from '@/context/LoginContext';

export default function LogoutPage() {
  const { authInfo } = useLogin();
  const { setIdentity } = useIdentity();
  const completeLogout = useCallback(() => {
    if (!authInfo?.logoutUrl) return;
    const redirectUrl = `${YST.HREF}?${qs.stringify({ client_id: YST.CLIENT_ID, redirect_uri: YST.REDIRECT_URI, response_type: YST.RESPONSE_TYPE })}`;
    window.location.href = `${authInfo.logoutUrl}?${qs.stringify({ client_id: YST.CLIENT_ID, post_logout_redirect_uri: redirectUrl })}`;
  }, [authInfo]);
  useEffect(() => {
    void logoutApi().then(() => {
      setIdentity(null);
      sessionStorage.removeItem(USER_INFO_KEY);
      completeLogout();
    }).catch((error) => console.error('登出失败', error));
  }, [completeLogout, setIdentity]);
  return <div>正在登出中</div>;
}
