# Standalone Login 后端能力

本工程由本地 Standalone Adapter 生成，使用模拟身份和签名 Cookie。此处不接入企业 Bee/YHT，不校验真实企业授权码。

## 与现有前端一致的登录接口

`GET /api/sso/login?code=mock-code`

响应为现有统一信封，前端 service 自动解包 body：

```json
{
  "returnCode": "SUC0000",
  "errorMsg": "",
  "body": {
    "userId": "member-001",
    "userName": "Local Admin",
    "avatar": "/avatar.svg"
  }
}
```

avatar 为可选字段；未配置时省略。响应通过 `Set-Cookie` 写入 `standalone_identity`，具有 HttpOnly、SameSite=Lax、Path=/、一小时有效期；HTTPS 时带 Secure。返回 `Cache-Control: no-store`，后续请求携带该 Cookie 即可恢复 TokenContextHolder 中的 sapId。

code 只需是非空、最多 2048 字符的模拟占位参数，不决定用户身份，不代表真实 SSO 验证；缺失/空白/超长返回 400，不发 Cookie。用户固定选取 `STANDALONE_MOCK_SSO_USER_ID`，未设置时选取 STANDALONE_MOCK_USERS 中第一位。

## 默认运行与可选覆盖

本 Standalone 工程默认启用 Mock、默认使用 local Profile 并绑定 `127.0.0.1:8080`。准备好数据库配置后直接启动 JAR，即可调用上述 GET 模拟回调，无需设置任何 Mock 环境变量或 Profile。

默认用户白名单为 `member-001 / Local Admin`、`member-002 / Local Member`；默认回调选择 member-001，响应省略 avatar。签名密钥与 POST 登录密钥在缺失或为空时，每次启动分别生成 32 字节随机值，仅保留在内存，不输出到日志或文件。重启后旧 Cookie 失效，需要重新登录。

仅 dev/local Profile 下注册 Mock，仍校验显式环回绑定。`STANDALONE_MOCK_ENABLED=false` 可关闭；仅 prod 等其他 Profile 不注册。以下均为可选覆盖，显式提供的非空密钥仍要求至少 32 字符：

```sh
export STANDALONE_MOCK_SIGNING_KEY="$(openssl rand -hex 32)"
export STANDALONE_MOCK_LOGIN_SECRET="$(openssl rand -hex 32)"
export STANDALONE_MOCK_USERS='{"member-001":{"userName":"Local Admin","avatar":"/avatar.svg"},"member-002":"Local Member"}'
export STANDALONE_MOCK_SSO_USER_ID=member-001
```

旧配置 `{"member-001":"Local Admin"}` 继续支持。缺失或空白用户配置使用默认白名单，非空但非法的用户配置拒绝启动。GET 模拟 SSO 接口不需要提交登录密钥。若需旧 POST 登录，请显式配置 STANDALONE_MOCK_LOGIN_SECRET；默认随机登录密钥不对外提供。

## 保留的测试接口

- `POST /api/standalone/login`：JSON userId/secret，仍校验共享登录密钥和用户白名单。
- `GET /api/standalone/me`：通过 Cookie 读取 userId/userName/avatar；未认证为 401。
- `POST /api/standalone/logout`：清除 Cookie 与当前上下文。JWT 无服务端吊销列表，旧 token 到期前仍可能重放，仅用于本地开发。

Cookie 由浏览器接收，前端无需读取 HttpOnly Cookie。现有 `/api` 开发代理可用于访问后端；不增加前端登录参数或修改统一响应解包。

## 权限边界

模拟认证不固定放行权限。userId/sapId 必须与 role_member.member_id 一致，后续 Authorization 仍查真实角色和资源数据。默认 member-001 与 fixture Bootstrap 管理员一致，但不会自动获得数据库权限。DB_URL、DB_USERNAME、DB_PASSWORD 和首次 Bootstrap 初始化仍由调用方准备；免 Mock 配置不代表数据库零配置。首次初始化及真实数据库授权由调用方手工验收。
