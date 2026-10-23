# Standalone Adapter（1016）

本工具只在 `.standalone-build/` 构造本地 Runtime Source；不修改正式模板、引擎算法或 HTTP 字段。上游基线、完整依赖扫描和覆盖清单见 [reports](reports/)。Java 8 字节码、Spring Boot 2.7.2、MyBatis-Plus 3.5.2、MySQL Connector/J 8.2.0；本次实际构建 JDK 为 18.0.2.1。需要 macOS/Linux、Python **3.9+**、Maven、JDK；Windows 请使用支持相同工具的 Git Bash/WSL。

## 启动引擎

在仓库根目录：

```sh
mvn -f template-engine/pom.xml -pl engine-service -am package
export TEMPLATE_ENGINE_STANDALONE_ENABLED=true
./devtools/standalone-adapter/run-engine.sh
```

引擎默认监听 `127.0.0.1:18080`，可以设置 `TEMPLATE_ENGINE_PORT`。无需设置第二个 Revision 变量；以 `release.yaml` 为准。`TEMPLATE_ENGINE_SOURCE_ROOT` 若已设置，必须与选择模式的绝对路径完全一致，否则拒绝启动。不要在 Source Root 末尾加 `/code`。

当前 Standalone Revision 为 `2026.10.23.1008`。更新 Adapter 或引擎后，先构建并停止已有 Standalone 引擎，再通过上述脚本重启，才会 prepare 并加载新模板源；已下载的生成工程不会自动改写。自 1007 起，含 Login 的生成后端默认可使用模拟登录，无需额外 Mock 环境变量，数据库仍需配置，详见下文。1008 额外适配前端 logoutApi 与本地登出页面。

默认关闭：

```sh
unset TEMPLATE_ENGINE_STANDALONE_ENABLED TEMPLATE_ENGINE_SOURCE_ROOT
./devtools/standalone-adapter/run-engine.sh
```

关闭时不执行 Python、不读取 Adapter 清单、不访问或创建临时构建目录。仍可完全绕过 Adapter，以仓库原有 `java -jar` + `application-local.yml` 命令启动；原生命令不解析新开关。此变量不是 HTTP 请求字段。

服务就绪用 `POST /v1/generate` 验证，没有约定的 `/health`。

## 构造、清单与进程保护

```sh
./devtools/standalone-adapter/build.sh prepare
./devtools/standalone-adapter/build.sh audit
./devtools/standalone-adapter/build.sh test
```

`manifest.yaml`、`release.yaml` 使用 **JSON 语法（合法 YAML 子集）**，因此 Python 不需要 PyYAML。不能改写成任意 YAML 语法。每个覆盖/删除/补丁目标都有实测 `expectedSourceSha256`；新增目标必须不存在。同一目标只能出现一次。

`patch` 文件采用 JSON 精确片段格式 `{ "before": "...", "after": "..." }`：旧片段必须出现恰好一次，结果还必须匹配 `expectedResultSha256`，不调用模糊补丁工具。Bootstrap 主体未复制重写，仅修改 JDBC URL 家族和对应错误标签。

复制边界与 `CodeTemplateReleaseLoader` 的 ownership roots 对齐：Contracts、两端 Base、Extension Manifest 和 Loader 使用的子树；不遍历 assembly、workspace、profiles；排除 node_modules/build/target、`.env`、macOS 缓存。所有输入须为 UTF-8 普通文件，拒绝 symlink、二进制、路径越界和大小写碰撞。

Standalone Revision 采用 `YYYY.MM.DD.N`，本地编号区间 `1000..999999`；这只是本地约定，不能保证跨仓库永不碰撞。正式 Source Revision 必须和 `release.yaml.sourceRevision` 一致。**变更上游 Runtime 输入、清单、覆盖内容或构造脚本后，人工审核并提升 standaloneRevision**。同一 Revision 的 fingerprint 写入 `.standalone-build/releases/`，禁止以不同内容重新发布；仅 Git SHA 改变而输入相同可复用并记录原因。不要删除版本台账来绕过检查。

构造在独立 staging 内完成，audit 通过才替换有效树。旧树先移至备份，提交失败则恢复；不宣称用一次 `mv` 原子覆盖非空目录。中断提交若留下 `previous-template-source`，先检查并恢复有效树再重试，脚本保守拒绝继续。

跨 macOS/Linux 使用原子 `mkdir` 锁。Standalone 服务通过 `exec` 继承锁 PID，活进程期间拒绝 prepare；正常停止后，下次启动确认 PID 已结束才回收锁。PID 状态不明确、跨主机或锁写入中断时保守失败；检查 `.release-lock/owner.json` 后处理，不能在进程存活时删锁。端口预检也拒绝已有监听者。模式/Revision 必须停进程后重启，不支持热替换。

`prepare` 不清理整个 `.standalone-build/`，不会删除生成工程、下载包、日志或隔离 Maven 缓存。

## HTTP 请求与自动验证

```sh
./devtools/standalone-adapter/scripts/http-smoke.sh \
  --base-url http://127.0.0.1:18080 --expected-mode standalone \
  --output-dir .standalone-build/http-verification
./devtools/standalone-adapter/build.sh verify-base --engine-url http://127.0.0.1:18080
./devtools/standalone-adapter/build.sh verify-full --engine-url http://127.0.0.1:18080 --manual-database
./devtools/standalone-adapter/build.sh verify-engine
./devtools/standalone-adapter/build.sh verify-all --engine-url http://127.0.0.1:18080 --manual-database
```

`verify-http-only` 是 `build.sh` 的 L1 快速入口。`verify-base` 构建/启动 Base；`verify-full` 覆盖 Base/Login/Authorization/Full；两者成功仅代表脚本明确执行的检查。`verify-all` 汇总 Adapter、HTTP、四工程构建/启动、无写库 Bootstrap 检查及原生引擎回归。**真实 MySQL 与实际 Executor Apply 未执行时返回 3（验收不完整）；失败返回 1，参数错误返回 2。不能把状态 3 当作全部通过。** `--skip-integration` 只表明跳过意图，仍不提升验收等级。

按本次用户要求，自动脚本不连接真实 MySQL：启动检查的数据源指向关闭端口，使用占位凭据，验证 Spring/Mapper Bean、真实 Mock HTTP 和匿名 RBAC 拒绝；Bootstrap 仅执行 `--dry-run` 与缺输入/非法 URL 故障用例。`--mysql-test-url` 只校验本地测试库地址，**不会自动启用写库**。

公开 Maven 校验同时使用测试专用 user/global settings 和 `.standalone-build/m2`，只访问 Maven Central；不读取公司镜像或 `~/.m2` 私有缓存。下载日志、依赖树、单元测试、JAR 日志及汇总报告在 `.standalone-build/reports/`，每次生成工程位于独立 `verify-*` 目录。下载仍要求可访问网络；不使用伪造 Parent/Jar、systemPath 或跳过测试掩盖问题。

最小请求：

```json
{"requestedConfig":{"capabilities":{}}}
```

Login：`{"requestedConfig":{"capabilities":{"login":{"enabled":true,"config":{}}}}}`。
Authorization：将 `login` 换为 `authorization`，引擎自动补 Login。Full 显式请求两者。完整请求样例见 [tests/requests](tests/requests/)，每次运行也会落在报告的 `requests/`。

V4 Update 顶层仅 `protocolVersion/currentTemplateState/requestedConfig/mode`；State 必须完整取自同一次模式、同一 Release 的 Generate/上轮 nextTemplateState。脚本自动串联 Base→Login→Authorization 并检查 `0→1→2`、payloadRef、SQL/双平台 Bootstrap 资产与重复 APPLY 的 204。Update ZIP 是操作包，**禁止直接解压覆盖工作区**。

Postman：Body/raw/JSON，`Content-Type: application/json`，`Accept: application/zip, application/json`，使用 Send and Download 保存 ZIP。原服务在错误请求只接受 ZIP 时存在错误内容协商限制，可能返回 500；双 Accept 可以接收合同规定的 JSON 错误，非法 `standalone` 字段仍为 400。本 Adapter 没有修改服务端错误处理。

L1 证明 HTTP/ZIP/规划；L2 需要真实数据库、授权与 Bootstrap；L3 需要现有 XCodeAgent Workspace Operation Executor 真正 Apply 后构建运行。脚本不会用 unzip 或自行编写的简化 Executor 冒充 L3。原生 API 没有 Variant 身份字段；独立 Revision 无法严格阻止全部跨模式 Update。自动客户端只使用本轮生成的同模式 State；生产调用方仍须做外部来源隔离。

## 本地登录与前端边界

正式前端 API/回调事实见 [login-contract.md](reports/login-contract.md)。现有前端可直接调用模拟回调：

Standalone 覆盖 Login 的 API 模块和 Logout 页面：`logoutApi()` 调用现有 `POST /api/standalone/logout` 清除 Cookie，成功后清除前端身份与缓存 Authorization，再返回本地首页；不跳转企业登出中心。其余前端 Runtime Source 保持上游内容。

- `GET /api/sso/login?code=mock-code`：返回 `SUC0000` 信封，body 为 `{userId, userName, avatar?}`，同时通过 Set-Cookie 写入 `standalone_identity`。
- `code` 是非空的模拟占位参数，不校验真实 YHT 授权码、不用于选择用户；缺失/空白/超过 2048 字符返回 400 且不发 Cookie。
- `STANDALONE_MOCK_SSO_USER_ID` 可指定 allowlist 内的固定测试身份；未设置时选择 `STANDALONE_MOCK_USERS` 中的第一位。请求参数不能任意冒充其他用户。该 GET 接口在明确启用的本地 Mock 模式下不需要共享登录密钥，不构成真实企业认证。
- `STANDALONE_MOCK_USERS` 兼容原字符串格式，也支持 `{"member-001":{"userName":"Local Admin","avatar":"/avatar.svg"}}`，缺少 avatar 时响应省略该字段。

原有测试接口继续保留：

- `POST /api/standalone/login`，JSON `{"userId":"member-001","secret":"<环境变量中的本地登录密钥>"}`。
- `GET /api/standalone/me`，读取身份 `userId/userName`。
- `POST /api/standalone/logout`，清除 Cookie 和当前上下文。

Standalone 生成后端默认启用 Mock，默认 Profile 为 `local`、监听 `127.0.0.1:8080`。数据库配置准备好后，直接启动 JAR 即可调用 `GET /api/sso/login?code=mock-code`，不需要任何 `STANDALONE_MOCK_*` 环境变量或显式设置 Profile。默认白名单为 `member-001 / Local Admin`、`member-002 / Local Member`，默认回调身份为第一位 `member-001`，不附带 avatar。

模拟接口和 Filter 仍仅在 **dev/local profile** 下注册，并校验显式 loopback 绑定。设置 `STANDALONE_MOCK_ENABLED=false` 可以关闭；只启用 `prod` 等其他 Profile 时不会注册 Mock。默认签名密钥与 POST 登录密钥由后端每次启动时分别使用 SecureRandom 生成 32 字节随机值，只保留在进程内存，不写入生成文件或日志；重启后旧 Cookie 失效，需要重新登录。Generate ZIP 不因随机密钥而变化。

以下配置均为可选覆盖。自定义非空密钥仍必须至少 32 字符；缺失或空字符串使用随机密钥。缺失或空白用户配置使用默认白名单，非空但非法的用户配置仍拒绝启动。若需使用旧 `POST /api/standalone/login`，请显式设置登录密钥，再在请求中提交同一值；缺省随机登录密钥不对外提供，默认使用 GET 模拟回调即可。

```sh
export STANDALONE_MOCK_SIGNING_KEY="$(openssl rand -hex 32)"
export STANDALONE_MOCK_LOGIN_SECRET="$(openssl rand -hex 32)"
export STANDALONE_MOCK_SSO_USER_ID=member-001
export STANDALONE_MOCK_USERS='{"member-001":"Local Admin","member-002":"Local Member"}'
```

以上用户标识与 fixture Bootstrap 管理员 `member-001` 一致。`POST /api/standalone/login` 仍需要本地共享登录密钥；`GET /api/sso/login` 仅返回上述配置的固定模拟身份，不能靠 userId/Header/code 切换为任意管理员。HS256 JWT 校验签名、issuer/audience/过期时间和 allowlist；HttpOnly/SameSite=Lax Cookie 在 HTTPS 时启用 Secure。ThreadLocal 在成功、异常、匿名后续请求时均清理，不继承到其他线程。登出清除浏览器 Cookie，不提供服务端 JWT 吊销；现有 token 到期前仍可能重放，仅用于本地开发。

权限继续由原始 `@RequireAnyResource`、TokenContext 和数据库角色/资源关系决定，没有 SYSTEM_ADMIN 特殊放行。测试中真实拦截器的有权/无权行为另有单元测试；真实 MySQL 权限数据需要下面的手工验收。YHT 标量只为保留 ApplicationConfigProjector 契约，本地登录不使用它们，也不承诺真实企业 SSO 可用。

## 手工数据库与 Bootstrap 验收（不由自动验证调用）

不要对原 `xcode` 业务库执行此测试。自行创建 `xcode_standalone_test` 空测试库并准备有权访问该库的测试用户，然后在终端设置：

```sh
export DB_URL='jdbc:mysql://127.0.0.1:3306/xcode_standalone_test?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai'
export DB_USERNAME='<测试用户>'
export DB_PASSWORD='<在本机填入密码，不写入仓库>'
```

先运行 `verify-full`，从 `.standalone-build/reports/verification-latest.md` 取得本次工程目录，再执行：

```sh
./devtools/standalone-adapter/scripts/manual-database.sh \
  --project .standalone-build/verify-<本次目录>/authorization --dry-run
./devtools/standalone-adapter/scripts/manual-database.sh \
  --project .standalone-build/verify-<本次目录>/authorization
# 再执行一次同样命令，验证真实初始化幂等
```

此助手只接受本地 `*_standalone_test` 库、当前 Standalone Revision 的 Authorization 工程；使用原生成工程的 Bootstrap shell 脚本，参数直接传递，支持含空格路径。SQL 固定 `backend/sql/authorization/schema.sql`，保留 Manifest v3、fingerprint、角色约束、事务和锁。凭据只经环境变量传递。手工使用生成的原始 Bootstrap 脚本时不会附带助手的测试库限制，请自行核对目标。

确认首次/重复执行后四张权限表及唯一约束正确，系统资源和 SYSTEM_ADMIN membership 无重复；并发执行两次验证锁规则。Fixture 业务默认授权为空，`member-001` 拥有系统授权管理入口，不代表拥有全部业务资源。

在上述数据库配置下启动生成的 Authorization JAR（默认 `127.0.0.1:8080`）。默认 GET 回调登录 member-001；若需要通过旧 POST 接口测试 member-002，额外设置上面的登录密钥。用自己的测试客户端维护 Cookie，检查：

1. 不登录请求 `GET /api/authorization/roles`：`returnCode=XCD1B11`。
2. 登录 member-002 请求相同接口：`returnCode=XCD1B10`（无管理权限）。
3. 登录 fixture 管理员 member-001：`returnCode=SUC0000`。
4. `GET /api/authorization/me/resources` 返回各自数据库资源；管理员并非固定全资源放行。
5. 缺 TechnicalPlan、SQL、数据库配置/密码时非零退出，不得把 dry-run 视作真实初始化通过。

授权错误沿用正式 Advice 的 HTTP 200 + 业务错误码；Mock 登录凭据错误/缺身份读取为 HTTP 401。引擎端口与生成后端端口不同。

## 回退与删除

停止 Standalone 引擎和生成的测试后端，unset 模式/Source Root，按原生命令重启。同一 Generate 请求应恢复 `ZA21:bee-starter-parent`，Login 恢复企业注解/依赖。本次已验证真实原生 Generate 对照；未在独立 Git 分支执行删除演练。

迁回行内时可删除 `devtools/standalone-adapter/` 与 `.standalone-build/`，并移除根 `.gitignore` 的一行忽略。正式 Engine/Template Source 不引用此目录，无额外 Profile/Bean/字段或持久化开关。绝不能删除正式 Base、Extension、SQL、Bootstrap 资产。企业 Backend 私仓编译/企业 SSO/TDSQL 运行未在本地验证。
