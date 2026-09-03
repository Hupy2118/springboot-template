import React, { PropsWithChildren, useMemo, useState } from 'react';
import useGuard from '@/hooks/useGuard';
import type { IUserInfo, IAuthInfo, IGlobalContext } from '@/login/types';
import { createContext } from 'react';
import { USER_INFO_KEY } from '@/login/constants';

const getStoredUserInfo = (): IUserInfo | null => {
  try {
    const stored = sessionStorage.getItem(USER_INFO_KEY);
    return stored ? JSON.parse(stored) as IUserInfo : null;
  } catch {
    return null;
  }
};

/**
 * 定义provider
 */
export const GlobalContext = createContext<IGlobalContext>({
  userInfo: null,
  authInfo: null,
  setUserInfo: () => {},
  setAuthInfo: () => {},
});

export const GlobalContextProvider = ({children}: PropsWithChildren) => {
  const [userInfo, setUserInfo] = useState<IUserInfo | null>(getStoredUserInfo);
  const [authInfo, setAuthInfo] = useState<IAuthInfo | null>(null);
  
  const GlobalContextValue = useMemo(
    () => ({
      userInfo,
      setUserInfo,
      authInfo,
      setAuthInfo
    }),
    [userInfo, authInfo, setUserInfo, setAuthInfo],
  );
      useGuard({ userInfo: GlobalContextValue.userInfo, setAuthInfo: GlobalContextValue.setAuthInfo});
  
  return (
    <GlobalContext.Provider value={GlobalContextValue}>
      {children}
    </GlobalContext.Provider>
  );
}

export default GlobalContextProvider;
