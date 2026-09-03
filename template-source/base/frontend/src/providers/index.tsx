import React, { PropsWithChildren, useMemo, useState } from 'react';
import useGuard from '@/hooks/useGuard';
import type { IUserInfo, IAuthInfo } from '@/typings';
import { IGlobalContext } from '@/typings/index';
import { createContext } from 'react';

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
  const [userInfo, setUserInfo] = useState<IUserInfo | null>(null);
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
