import { useCallback, useEffect, useContext } from 'react';
import { useNavigate } from 'react-router-dom';
import qs from 'qs';
import { CURRENT_URL, USER_INFO_KEY } from '@/constants';
import { YST } from '@/constants/yst';
import { logoutApi } from '@/apis/login';
import { GlobalContext } from '@/providers/index';

export default function Logout() {
  const prevUrl = window.localStorage.getItem(CURRENT_URL);

  const navigate = useNavigate();
  const replace = useCallback((url: string) => navigate(url, { replace: true }), [navigate]);

  const { authInfo, setUserInfo } = useContext(GlobalContext);

      const handleLogout = useCallback(() => {
    // 登出后自动跳转登录
    const redirectUrl = `${YST.HREF}?${qs.stringify({
      client_id: YST.CLIENT_ID,
      redirect_uri: YST.REDIRECT_URI,
      response_type: YST.RESPONSE_TYPE,
    })}`;
    // 登出Url的查询参数
    const endInfo = {
      client_id: YST.CLIENT_ID,
      post_logout_redirect_uri: redirectUrl,
    };

    window.location.href = `${authInfo?.logoutUrl}?${qs.stringify(endInfo)}`;
  }, [authInfo]);
  
  useEffect(() => {
    logoutApi()
      .then(() => {
        setUserInfo(() => null);
        sessionStorage.removeItem(USER_INFO_KEY);
        handleLogout();
      })
      .catch((e) => {
        console.log('登出失败', e);
      })
  }, [authInfo, handleLogout, prevUrl, replace, setUserInfo]);

  return <div>正在登出中</div>;
}
