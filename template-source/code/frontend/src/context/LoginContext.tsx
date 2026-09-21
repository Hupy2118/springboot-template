import { createContext, useContext, type Dispatch, type SetStateAction } from 'react';
import type { LoginInfo } from '@/typings/login';

export interface LoginContextValue {
  authInfo: LoginInfo | null;
  setAuthInfo: Dispatch<SetStateAction<LoginInfo | null>>;
}

export const LoginContext = createContext<LoginContextValue>({
  authInfo: null,
  setAuthInfo: () => undefined,
});

export const useLogin = () => useContext(LoginContext);
