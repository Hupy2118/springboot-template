/**
 * 全局通用类型定义
 */
export type Option = { label: string; value: string };

export interface IUserInfo {
  userId: string;
  userName: string;
  userNo: string;
  avatar?: string;
  pathName?: string;
}

export enum AuthnSourceEnum {
  YHT = 'YHT'
}

export interface IAuthInfo {
  loginUrl: string; // 认证体系登录地址
  logoutUrl: string; // 认证体系登出地址，用来判断是否显示登出按钮
  loginRoute: string; // 认证体系登录后的前端跳转路由
  logoutRoute: string; // 认证体系登出后的前端跳转路由
  authnSource: AuthnSourceEnum; // 登录源：YHT 或者 别的登录方式
}

export interface IGlobalContext {
  userInfo: IUserInfo | null;
  authInfo: IAuthInfo | null;
  setUserInfo: React.Dispatch<React.SetStateAction<IUserInfo | null>>;
  setAuthInfo: React.Dispatch<React.SetStateAction<IAuthInfo | null>>;
}
