import { createContext, type Dispatch, type PropsWithChildren, type SetStateAction, useMemo, useState } from 'react';
import type { IUserInfo } from '@/login/types';
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
export interface GlobalContextValue {
  userInfo: IUserInfo | null;
  setUserInfo: Dispatch<SetStateAction<IUserInfo | null>>;
}

export const GlobalContext = createContext<GlobalContextValue>({
  userInfo: null,
  setUserInfo: () => {},
});

export const GlobalContextProvider = ({children}: PropsWithChildren) => {
  const [userInfo, setUserInfo] = useState<IUserInfo | null>(getStoredUserInfo);
  const GlobalContextValue = useMemo(() => ({ userInfo, setUserInfo }), [userInfo]);
  
  return (
    <GlobalContext.Provider value={GlobalContextValue}>
      {children}
    </GlobalContext.Provider>
  );
};

export default GlobalContextProvider;
