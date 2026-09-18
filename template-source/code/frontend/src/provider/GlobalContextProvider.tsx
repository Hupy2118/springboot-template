import React, { PropsWithChildren, useMemo, useState } from 'react';
import useLoginGuard from '@/hooks/useLoginGuard';
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

export const GlobalContextProvider = ({ children }: PropsWithChildren) => {
  const [userInfo, setUserInfo] = useState<IUserInfo | null>(null);
  const [authInfo, setAuthInfo] = useState<IAuthInfo | null>(null);

  const GlobalContextValue = useMemo(
    () => ({
      userInfo,
      setUserInfo,
      authInfo,
      setAuthInfo,
    }),
    [userInfo, authInfo, setUserInfo, setAuthInfo],
  );

  // login开启时才有
  useLoginGuard({
    userInfo: GlobalContextValue.userInfo,
    setAuthInfo: GlobalContextValue.setAuthInfo,
  });

  return (
    <GlobalContext.Provider value={GlobalContextValue}>
      {children}
    </GlobalContext.Provider>
  );
};

export default GlobalContextProvider;
