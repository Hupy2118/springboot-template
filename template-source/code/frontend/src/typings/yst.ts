export type YstConfigType = {
  CLIENT_ID: string; // YST应用的认证Id
  REDIRECT_URI: string; // YST认证完成后用于回跳的业务系统地址
  LOGOUT_URI: string; // 业务系统在执行YST登出前的地址
  HREF: string; // 一号通的认证地址
  END_URL: string; // 一号通的登出地址
  RESPONSE_TYPE: string; // 一号通认证完成的响应类型
  LOGOUT_SHARE_JS: string; // 同步一事通登出状态的JS文件地址
  ENV: 'prod' | 'biz' | 'test' | 'local'// 当前对接的一号通环境，这个local只是用于解决从github上拉取模板工程时的跨域问题，实际行内是没有这个local的
}