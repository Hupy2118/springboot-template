import { useEffect, useContext } from 'react';
import { useNavigate } from 'react-router-dom';
import { USER_INFO_KEY } from '@/login/constants';
import { GlobalContext } from '@/providers/index';

export default function Logout() {
  const navigate = useNavigate();
  const { setUserInfo } = useContext(GlobalContext);
  
  useEffect(() => {
    setUserInfo(null);
    sessionStorage.removeItem(USER_INFO_KEY);
    navigate('/login', { replace: true });
  }, [navigate, setUserInfo]);

  return <div>正在登出中</div>;
}
