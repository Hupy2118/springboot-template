export enum AuthnSourceEnum {
  YHT = 'YHT',
}

export interface LoginInfo {
  loginUrl: string;
  logoutUrl: string;
  loginRoute: string;
  logoutRoute: string;
  authnSource: AuthnSourceEnum;
}
